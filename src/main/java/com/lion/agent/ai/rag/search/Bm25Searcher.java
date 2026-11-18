package com.lion.agent.ai.rag.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.ai.rag.mapper.RagDocumentMapper;
import com.lion.agent.ai.rag.mapper.RagSegmentMapper;
import com.lion.agent.ai.rag.pojo.entity.RagDocumentEntity;
import com.lion.agent.ai.rag.pojo.entity.RagSegmentEntity;
import com.lion.agent.ai.rag.service.impl.RagServiceImpl;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 关键词检索(混合检索的"字面"召回路):
 * 数据源为 rag_segment 表(与 Milvus 向量同步落库), 打分在应用内存完成。
 *
 * 为什么需要这一路: 向量检索对"专有名词/编号/报错原文/函数名"这类稀有精确 token 很弱
 * (稀有词在向量空间被稀释), BM25 的 IDF 机制对稀有词给高分, 恰好补上;
 * 反之同义改写只有向量能接住 —— 两路互补, 见 HybridRetriever。
 *
 * 规模边界: 每次查询全量加载片段构建临时索引, 片段量在数千以内足够;
 * 生产规模化应换 ES/OpenSearch 或 Milvus sparse(BM25 Function), 接口不变直接换实现。
 *
 * 分词策略: 英文/数字按连续词切, 中文按二字组(bigram)切 —— 中文单字信息量低,
 * bigram 兼顾精确术语(如"存储过程")与召回率, 是轻量场景的通用做法。
 */
@Slf4j
@Component
public class Bm25Searcher {

    /** BM25 经典参数: 词频饱和度(越大词频影响越持久) */
    private static final double K1 = 1.5;
    /** BM25 长度归一化(0 = 不按文档长度惩罚, 1 = 完全按长度归一) */
    private static final double B = 0.75;

    private static final Pattern LATIN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}+");

    private final RagSegmentMapper segmentMapper;
    private final RagDocumentMapper documentMapper;

    public Bm25Searcher(RagSegmentMapper segmentMapper, RagDocumentMapper documentMapper) {
        this.segmentMapper = segmentMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * BM25 检索: 返回得分 > 0 的前 topK 条(按分倒序)。
     * 片段元数据(docId/fileName/page)补齐后与向量路统一为 RetrievedSegment。
     */
    public List<RetrievedSegment> search(String query, int topK) {
        List<RagSegmentEntity> all = segmentMapper.selectList(null);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, String> fileNames = documentMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RagDocumentEntity::getId, RagDocumentEntity::getFileName, (a, b) -> a));

        // 构建临时倒排索引: 词 → 各片段中该词的出现次数
        List<List<String>> docTokens = new ArrayList<>(all.size());
        Map<String, int[]> docTermFreq = new HashMap<>();
        double totalLen = 0;
        for (RagSegmentEntity seg : all) {
            List<String> tokens = tokenize(seg.getContent());
            docTokens.add(tokens);
            totalLen += tokens.size();
            for (String t : tokens) {
                docTermFreq.computeIfAbsent(t, k -> new int[all.size()])[docTokens.size() - 1]++;
            }
        }
        double avgLen = totalLen / Math.max(all.size(), 1);

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        // BM25 打分: IDF × 词频饱和 × 长度归一
        double[] scores = new double[all.size()];
        int n = all.size();
        for (String t : queryTokens) {
            int[] freqs = docTermFreq.get(t);
            if (freqs == null) {
                continue;
            }
            int df = 0;
            for (int f : freqs) {
                if (f > 0) {
                    df++;
                }
            }
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                int f = freqs[i];
                if (f == 0) {
                    continue;
                }
                double len = docTokens.get(i).size();
                scores[i] += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * len / avgLen));
            }
        }

        // 取得分 > 0 的 topK(简单选择排序, 候选量小无需堆)
        List<Integer> hit = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0) {
                hit.add(i);
            }
        }
        hit.sort((a, b) -> Double.compare(scores[b], scores[a]));
        List<RetrievedSegment> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, hit.size()); i++) {
            int idx = hit.get(i);
            RagSegmentEntity seg = all.get(idx);
            Metadata meta = new Metadata()
                    .put(RagServiceImpl.META_DOC_ID, String.valueOf(seg.getDocId()))
                    .put(RagServiceImpl.META_FILE_NAME,
                            fileNames.getOrDefault(seg.getDocId(), "未知文档"));
            if (seg.getPage() != null) {
                meta.put(RagServiceImpl.META_PAGE, String.valueOf(seg.getPage()));
            }
            result.add(new RetrievedSegment(TextSegment.from(seg.getContent(), meta), null));
        }
        if (!result.isEmpty()) {
            log.debug("BM25 召回 {}/{} 条(片段总量 {})", result.size(), topK, n);
        }
        return result;
    }

    /** 分词: 英文/数字连续词(小写) + 中文二字组(单字 run 保留单字) */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher m = LATIN.matcher(text);
        while (m.find()) {
            tokens.add(m.group().toLowerCase(Locale.ROOT));
        }
        m = HAN.matcher(text);
        while (m.find()) {
            String run = m.group();
            if (run.length() == 1) {
                tokens.add(run);
            } else {
                for (int i = 0; i < run.length() - 1; i++) {
                    tokens.add(run.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }
}
package com.lion.agent.ai.rag.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器(生产 RAG 标准召回架构):
 *   向量路(语义, Top-DENSE) + BM25 路(字面, Top-KEYWORD) → RRF 倒数排名融合 → 候选片段
 *
 * RRF(Reciprocal Rank Fusion): 每路按名次贡献 1/(K + rank) 分(K=60 经验值),
 * 同一片段出现在两路则分数累加 —— 只做排名融合不比较分数绝对值, 避开
 * "余弦相似度与 BM25 分数量纲不同无法直接加权"的问题(Elasticsearch 同款默认策略)。
 *
 * 参数为代码常量: 召回量放宽(向量 20/分数 0.6 起)让 rerank 有挑选空间,
 * 最终质量由精排(RagReranker)收口。
 */
@Slf4j
@Component
public class HybridRetriever {

    /** 向量路召回条数(放宽给 rerank 挑选, 原 Top5 直用是"无精排"时代参数) */
    private static final int DENSE_TOP_K = 20;
    /** 向量路最低相似度(较原来 0.75 放宽: 边缘片段进精排池, 由 rerank 决定去留) */
    private static final double DENSE_MIN_SCORE = 0.6;
    /** BM25 路召回条数 */
    private static final int KEYWORD_TOP_K = 10;
    /** 融合后送精排的候选条数 */
    private static final int FUSE_TOP_K = 10;
    /** RRF 平滑常数(越大排名差异影响越小, 60 为论文/ES 默认经验值) */
    private static final int RRF_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Bm25Searcher bm25Searcher;

    public HybridRetriever(EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> embeddingStore,
                           Bm25Searcher bm25Searcher) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.bm25Searcher = bm25Searcher;
    }

    /**
     * 双路召回 + RRF 融合。返回按融合分倒序的前 FUSE_TOP_K 条候选;
     * 两路都空返回空列表(调用方短路为"没有相关资料")。
     */
    public List<RetrievedSegment> retrieve(String query) {
        // 1. 向量路: 问题向量化 → 相似度检索
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> dense = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(DENSE_TOP_K)
                .minScore(DENSE_MIN_SCORE)
                .build()).matches();
        List<RetrievedSegment> denseList = dense.stream()
                .map(m -> new RetrievedSegment(m.embedded(), m.score()))
                .toList();

        // 2. BM25 路: 关键词字面召回(任意一路空都不影响另一路)
        List<RetrievedSegment> keywordList;
        try {
            keywordList = bm25Searcher.search(query, KEYWORD_TOP_K);
        } catch (Exception e) {
            log.warn("BM25 召回失败, 本次仅用向量路: {}", e.getMessage());
            keywordList = List.of();
        }

        // 3. RRF 融合(按片段身份 docId:text 去重, 两路同时命中则分数累加)
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, RetrievedSegment> candidates = new LinkedHashMap<>();
        accumulate(fused, candidates, denseList);
        accumulate(fused, candidates, keywordList);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<RetrievedSegment> result = new ArrayList<>();
        for (int i = 0; i < Math.min(FUSE_TOP_K, ranked.size()); i++) {
            result.add(candidates.get(ranked.get(i).getKey()));
        }
        log.info("混合检索: 向量 {} 条 + 关键词 {} 条 → 融合候选 {} 条",
                denseList.size(), keywordList.size(), result.size());
        return result;
    }

    /** 单路结果按名次累加 RRF 分; 元数据更完整的向量路片段优先保留 */
    private void accumulate(Map<String, Double> fused, Map<String, RetrievedSegment> candidates,
                            List<RetrievedSegment> list) {
        int rank = 1;
        for (RetrievedSegment item : list) {
            String key = identity(item);
            fused.merge(key, 1.0 / (RRF_K + rank), Double::sum);
            candidates.putIfAbsent(key, item);
            rank++;
        }
    }

    /** 片段身份: docId + 文本(同 docId 同文本视为同一段落, 跨路命中合并加分) */
    private String identity(RetrievedSegment item) {
        String docId = item.segment().metadata().getString("docId");
        return docId + ":" + item.segment().text().hashCode();
    }
}
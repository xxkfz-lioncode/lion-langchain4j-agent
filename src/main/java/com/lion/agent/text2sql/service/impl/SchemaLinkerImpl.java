package com.lion.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.text2sql.config.Text2SqlProperties;
import com.lion.agent.text2sql.entity.DataSchemaColumnEntity;
import com.lion.agent.text2sql.entity.DataSchemaTableEntity;
import com.lion.agent.text2sql.entity.DataTermEntity;
import com.lion.agent.text2sql.mapper.DataSchemaColumnMapper;
import com.lion.agent.text2sql.mapper.DataSchemaTableMapper;
import com.lion.agent.text2sql.mapper.DataTermMapper;
import com.lion.agent.text2sql.service.SchemaLinker;
import com.lion.agent.text2sql.service.SchemaManager;
import com.lion.agent.text2sql.util.RrfMerger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema Linking 实现: 关键词召回 + 规则召回 → RRF 融合 → top-K。
 * <p>
 * <b>召回策略</b>(设计文档 §5.2):
 * <ul>
 *   <li>① 关键词召回: 问题中是否出现表名 / 表注释 / 字段名 / 字段注释 / 值域描述</li>
 *   <li>② 规则召回: 问题显式提到表名、命中术语词典</li>
 *   <li>③ 向量召回: 骨架阶段预留(Milvus), 后续接入</li>
 * </ul>
 */
@Slf4j
@Service
public class SchemaLinkerImpl implements SchemaLinker {

    private final SchemaManager schemaManager;
    private final DataSchemaTableMapper tableMapper;
    private final DataSchemaColumnMapper columnMapper;
    private final DataTermMapper termMapper;
    private final Text2SqlProperties props;

    public SchemaLinkerImpl(SchemaManager schemaManager,
                            DataSchemaTableMapper tableMapper,
                            DataSchemaColumnMapper columnMapper,
                            DataTermMapper termMapper,
                            Text2SqlProperties props) {
        this.schemaManager = schemaManager;
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.termMapper = termMapper;
        this.props = props;
    }

    /** 单路召回项(带分) */
    private record Hit(String tableName, double score) {
    }

    @Override
    public RecallResult recall(String question, Long userId) {
        if (!StringUtils.hasText(question)) {
            return RecallResult.empty();
        }

        // 1. 加载候选(仅允许查询的表)
        List<DataSchemaTableEntity> tables = tableMapper.selectList(
                new LambdaQueryWrapper<DataSchemaTableEntity>()
                        .eq(DataSchemaTableEntity::getIsQueryable, 1)
                        .orderByDesc(DataSchemaTableEntity::getRowCount));
        if (tables.isEmpty()) {
            log.warn("[text2sql] 无可查询表, 请先刷新元数据");
            return RecallResult.empty();
        }

        Map<String, List<DataSchemaColumnEntity>> columnCache = new HashMap<>();
        for (DataSchemaTableEntity t : tables) {
            columnCache.put(t.getTableName(), columnMapper.selectList(
                    new LambdaQueryWrapper<DataSchemaColumnEntity>()
                            .eq(DataSchemaColumnEntity::getTableName, t.getTableName())));
        }

        // 2. 关键词召回
        List<Hit> keywordHits = keywordRecall(question, tables, columnCache);
        // 3. 规则召回(显式表名 + 术语)
        List<Hit> ruleHits = ruleRecall(question, tables, columnCache);

        // 4. RRF 融合
        List<List<Hit>> sources = List.of(keywordHits, ruleHits);
        List<String> sourceNames = List.of("keyword", "rule");
        List<RrfMerger.Scored<Hit>> merged = RrfMerger.merge(sources, Hit::tableName, sourceNames, props.getRecallTopK());

        List<String> recalled = new ArrayList<>();
        for (RrfMerger.Scored<Hit> s : merged) {
            recalled.add(s.item().tableName());
        }

        if (recalled.isEmpty()) {
            // 兜底: 骨架阶段表很少, 关键词没命中就直接把可查询表全部给 LLM,
            // 避免"明明有表却提示找不到"的死胡同; 表多了以后应改为向量召回。
            recalled = tables.stream()
                    .limit(props.getRecallTopK())
                    .map(DataSchemaTableEntity::getTableName)
                    .toList();
            if (recalled.isEmpty()) {
                log.info("[text2sql] 未召回任何表, question={}", question);
                return RecallResult.empty();
            }
            log.info("[text2sql] 关键词/规则均未命中, 回退为全表召回 {} (question={})", recalled, question);
        }

        String digest = schemaManager.buildSchemaDigest(recalled);
        List<String> termsHit = extractTerms(question);
        log.info("[text2sql] 召回表 {} 术语 {} (question={})", recalled, termsHit, question);
        return new RecallResult(recalled, digest, termsHit);
    }

    /**
     * 关键词召回: 遍历表/字段, 计算问题与其描述的重合度。
     * 注释类文本走分词匹配(见 {@link #matchesText}), 否则"订单金额(元)"这类带括号的注释永远匹配不上。
     */
    private List<Hit> keywordRecall(String question,
                                    List<DataSchemaTableEntity> tables,
                                    Map<String, List<DataSchemaColumnEntity>> columnCache) {
        List<Hit> hits = new ArrayList<>();
        for (DataSchemaTableEntity t : tables) {
            double score = 0;
            if (contains(question, t.getTableName())) {
                score += 3;
            }
            if (matchesText(question, t.getTableComment())) {
                score += 2;
            }
            for (DataSchemaColumnEntity c : columnCache.getOrDefault(t.getTableName(), List.of())) {
                if (contains(question, c.getColumnName())) {
                    score += 2;
                }
                if (matchesText(question, c.getColumnComment()) || matchesText(question, c.getLlmDescription())) {
                    score += 2;
                }
                if (matchesText(question, c.getValueDomain())) {
                    score += 1;
                }
            }
            if (score > 0) {
                hits.add(new Hit(t.getTableName(), score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits;
    }

    /**
     * 规则召回: 显式表名 / 术语词典命中。
     */
    private List<Hit> ruleRecall(String question,
                                 List<DataSchemaTableEntity> tables,
                                 Map<String, List<DataSchemaColumnEntity>> columnCache) {
        List<Hit> hits = new ArrayList<>();
        for (DataSchemaTableEntity t : tables) {
            if (contains(question, t.getTableName()) || matchesText(question, t.getTableComment())) {
                hits.add(new Hit(t.getTableName(), 1.0));
            }
        }
        // 术语命中 → 其绑定表
        List<DataTermEntity> terms = termMapper.selectList(new LambdaQueryWrapper<DataTermEntity>()
                .eq(DataTermEntity::getEnabled, 1));
        for (DataTermEntity term : terms) {
            if (matchTerm(question, term)) {
                String table = term.getTableName();
                if (StringUtils.hasText(table) && hits.stream().noneMatch(h -> h.tableName().equals(table))) {
                    hits.add(new Hit(table, 1.5));
                }
            }
        }
        return hits;
    }

    /** 提取问题中命中的术语(用于日志与 prompt 注入) */
    private List<String> extractTerms(String question) {
        List<String> hit = new ArrayList<>();
        List<DataTermEntity> terms = termMapper.selectList(new LambdaQueryWrapper<DataTermEntity>()
                .eq(DataTermEntity::getEnabled, 1));
        for (DataTermEntity term : terms) {
            if (matchTerm(question, term)) {
                hit.add(term.getTerm());
            }
        }
        return hit;
    }

    private boolean matchTerm(String question, DataTermEntity term) {
        if (contains(question, term.getTerm())) {
            return true;
        }
        String aliases = term.getAliases();
        if (StringUtils.hasText(aliases)) {
            for (String alias : aliases.split("[,，、]")) {
                if (StringUtils.hasText(alias) && contains(question, alias.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 包含判断: 空串不算命中, 且统一忽略大小写。
     */
    private boolean contains(String question, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        return question.toLowerCase().contains(candidate.toLowerCase());
    }

    /**
     * 注释类文本匹配: 先整串匹配, 不中再按分词结果匹配。
     * <p>
     * 用于表/字段注释这类"描述性"文本 —— 它们通常带括号补充说明或多个短语,
     * 例如 {@code 订单金额(元)} / {@code 订单表(问数演示目标表)},
     * 用户问"订单金额排名"时整串匹配必然失败, 但分词后的"订单金额"能命中。
     */
    private boolean matchesText(String question, String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (contains(question, text)) {
            return true;
        }
        for (String kw : extractKeywords(text)) {
            if (question.toLowerCase().contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从描述性文本中提取可用于召回的关键词。
     * <ul>
     *   <li>去掉括号内的补充说明</li>
     *   <li>按中英文标点/空白切分</li>
     *   <li>去掉表名后缀"表"(保留 >= 2 字的词根)</li>
     *   <li>长度不足 2 的词丢弃, 避免单字误召回</li>
     * </ul>
     */
    private List<String> extractKeywords(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String cleaned = text.replaceAll("[（(【\\[].*?[)）\\]】]", " ");
        String[] parts = cleaned.split("[\\s,，、;；:：.。/|]+");
        List<String> keywords = new ArrayList<>();
        for (String part : parts) {
            String word = part.trim().toLowerCase();
            if (word.length() < 2) {
                continue;
            }
            keywords.add(word);
            if (word.endsWith("表") && word.length() > 2) {
                keywords.add(word.substring(0, word.length() - 1));
            }
        }
        return keywords;
    }
}

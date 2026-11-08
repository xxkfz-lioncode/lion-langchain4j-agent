package com.lion.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.text2sql.entity.DataFewshotEntity;
import com.lion.agent.text2sql.mapper.DataFewshotMapper;
import com.lion.agent.text2sql.service.FewshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Few-shot 检索实现。
 * <p>
 * 骨架阶段: 关键词重合度 + 热度/评分排序(纯 Java 计算, 不依赖向量库)。
 * 后续可切换为 Milvus 向量召回 + RRF。
 */
@Slf4j
@Service
public class FewshotStoreImpl implements FewshotStore {

    private final DataFewshotMapper fewshotMapper;

    public FewshotStoreImpl(DataFewshotMapper fewshotMapper) {
        this.fewshotMapper = fewshotMapper;
    }

    @Override
    public List<Example> retrieve(String question, int topK) {
        if (!StringUtils.hasText(question) || topK <= 0) {
            return List.of();
        }
        List<DataFewshotEntity> all = fewshotMapper.selectList(new LambdaQueryWrapper<DataFewshotEntity>()
                .eq(DataFewshotEntity::getEnabled, 1)
                .isNotNull(DataFewshotEntity::getSqlText));
        if (all.isEmpty()) {
            return List.of();
        }

        String q = question.toLowerCase();
        List<Scored> scored = new ArrayList<>();
        for (DataFewshotEntity e : all) {
            double score = similarity(q, e.getQuestion());
            // 热度与评分加权, 避免冷启动时只有相似度
            double rating = e.getAvgRating() == null ? 5.0 : e.getAvgRating().doubleValue();
            double useCount = e.getUseCount() == null ? 0 : e.getUseCount();
            score = score + rating * 0.05 + Math.log1p(useCount) * 0.02;
            if (score > 0) {
                scored.add(new Scored(e, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Example> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            DataFewshotEntity e = scored.get(i).entity();
            result.add(new Example(e.getQuestion(), e.getSqlText(), e.getTables(), e.getExplanation()));
        }
        return result;
    }

    @Override
    public void incrementUseCount(Long id) {
        if (id == null) {
            return;
        }
        DataFewshotEntity entity = fewshotMapper.selectById(id);
        if (entity != null) {
            entity.setUseCount((entity.getUseCount() == null ? 0 : entity.getUseCount()) + 1);
            fewshotMapper.updateById(entity);
        }
    }

    /**
     * 相似度: 问题中 2-gram 重合比例(Jaccard)。
     */
    private double similarity(String q, String target) {
        if (!StringUtils.hasText(target)) {
            return 0;
        }
        String t = target.toLowerCase();
        if (q.equals(t)) {
            return 1.0;
        }
        // 2-gram
        java.util.Set<String> qGrams = grams(q);
        java.util.Set<String> tGrams = grams(t);
        if (qGrams.isEmpty() || tGrams.isEmpty()) {
            return 0;
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(qGrams);
        intersection.retainAll(tGrams);
        java.util.Set<String> union = new java.util.HashSet<>(qGrams);
        union.addAll(tGrams);
        return (double) intersection.size() / union.size();
    }

    private java.util.Set<String> grams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        String cleaned = s.replaceAll("[\\s\\p{Punct}]+", "");
        for (int i = 0; i + 1 < cleaned.length(); i++) {
            set.add(cleaned.substring(i, i + 2));
        }
        return set;
    }

    private record Scored(DataFewshotEntity entity, double score) {
    }
}

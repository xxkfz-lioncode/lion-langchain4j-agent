package com.lion.agent.text2sql.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * RRF(Reciprocal Rank Fusion, 倒数排名融合)。
 * <p>
 * 各召回路的分数尺度不同(关键词是相关度, 向量是余弦距离), 直接用分数融合会被
 * 某一路主导。RRF 只用<b>排名</b>融合, 鲁棒且免调参:
 * <pre>
 * score(d) = Σ 1 / (k + rank_i(d))   , k = 60
 * </pre>
 * 同时记录每个结果被哪些召回路命中(用于解释"为什么召回它")。
 *
 * @param <T> 召回项类型
 */
public final class RrfMerger {

    /** 标准 RRF 平滑常数 */
    public static final int DEFAULT_K = 60;

    private RrfMerger() {
    }

    /** 融合后的带分数结果 */
    public record Scored<T>(T item, double score, Set<String> hitSources) {
    }

    /**
     * 融合多路召回结果。
     *
     * @param sources    多路召回结果(每路内部已按相关性排序)
     * @param extractor  从召回项中取去重键
     * @param sourceName 每路召回的名称(与 sources 顺序一致, 用于解释来源)
     * @param topN       返回数量(≤0 表示不限制)
     * @return 按 RRF 分数降序的结果
     */
    public static <T> List<Scored<T>> merge(List<List<T>> sources,
                                                Function<T, String> extractor,
                                                List<String> sourceName,
                                                int topN) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, T> itemMap = new HashMap<>();
        Map<String, Set<String>> sourceMap = new HashMap<>();

        for (int s = 0; s < sources.size(); s++) {
            List<T> source = sources.get(s);
            if (source == null) {
                continue;
            }
            String name = (sourceName != null && s < sourceName.size()) ? sourceName.get(s) : ("source" + s);
            for (int i = 0; i < source.size(); i++) {
                T item = source.get(i);
                String key = extractor.apply(item);
                if (key == null || key.isEmpty()) {
                    continue;
                }
                scoreMap.merge(key, 1.0 / (DEFAULT_K + i + 1), Double::sum);
                itemMap.putIfAbsent(key, item);
                sourceMap.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(name);
            }
        }

        List<Scored<T>> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : scoreMap.entrySet()) {
            result.add(new Scored<>(itemMap.get(e.getKey()), e.getValue(), sourceMap.get(e.getKey())));
        }
        result.sort(Comparator.<Scored<T>>comparingDouble(Scored::score).reversed());

        if (topN > 0 && result.size() > topN) {
            return new ArrayList<>(result.subList(0, topN));
        }
        return result;
    }

    /** 便捷重载: 不限制返回数量 */
    public static <T> List<Scored<T>> merge(List<List<T>> sources,
                                                Function<T, String> extractor,
                                                List<String> sourceName) {
        return merge(sources, extractor, sourceName, 0);
    }
}

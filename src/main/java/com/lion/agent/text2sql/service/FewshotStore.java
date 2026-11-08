package com.lion.agent.text2sql.service;

import java.util.List;

/**
 * Few-shot 示例检索: 从历史高质量「问题 → SQL」中召回最相似的示例注入 prompt。
 * <p>
 * 这是提升准确率最有效的手段之一 —— 与其让模型猜, 不如给它看同类问题的标准答案。
 */
public interface FewshotStore {

    /** Few-shot 示例 */
    record Example(String question, String sql, String tables, String explanation) {
    }

    /**
     * 检索最相似的 few-shot 示例。
     *
     * @param question 用户问题
     * @param topK     数量
     * @return 示例列表(按相似度/热度排序)
     */
    List<Example> retrieve(String question, int topK);

    /** 使用计数 +1(用于热度排序) */
    void incrementUseCount(Long id);
}

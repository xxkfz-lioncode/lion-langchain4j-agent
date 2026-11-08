package com.lion.agent.text2sql.service;

import java.util.List;

/**
 * Schema Linking: 从用户问题召回最相关的 top-K 表。
 * <p>
 * 这是决定 SQL 准确率的关键环节 —— 几百张表全塞进 prompt 既超 token 又干扰模型,
 * 必须精准召回。
 */
public interface SchemaLinker {

    /** 召回结果 */
    record RecallResult(List<String> tables, String schemaDigest, List<String> termsHit) {

        public static RecallResult empty() {
            return new RecallResult(List.of(), "", List.of());
        }
    }

    /**
     * 召回主入口。
     *
     * @param question 用户问题
     * @param userId   用户id(用于后续会话高频表, 骨架暂不用)
     * @return 召回的表 + schema digest + 命中的术语
     */
    RecallResult recall(String question, Long userId);
}

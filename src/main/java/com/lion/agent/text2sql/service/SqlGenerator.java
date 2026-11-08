package com.lion.agent.text2sql.service;

import java.util.List;

/**
 * SQL 生成: 基于 schema digest + few-shot + 术语, 调用 LLM 生成只读 SQL。
 * <p>
 * 设计要点(见设计文档 §5.3):
 * <ul>
 *   <li>结构化输出 JSON(sql / explanation / chartType / clarification / intent)</li>
 *   <li>歧义时返回 clarification 而非猜测</li>
 *   <li>执行报错后可带错误信息重新生成(错误自愈)</li>
 * </ul>
 */
public interface SqlGenerator {

    /** 生成结果 */
    record SqlGenerationResult(String sql,
                               String explanation,
                               String chartType,
                               String clarification,
                               String intent) {

        public static SqlGenerationResult clarify(String question) {
            return new SqlGenerationResult(null, null, "TABLE", question, "CLARIFY");
        }
    }

    /**
     * 主生成。
     *
     * @param question    用户问题
     * @param schemaDigest schema 摘要
     * @param termsHit    命中的术语(可空)
     * @param fewshots    few-shot 示例(可空)
     * @param userId      用户id(用于日志上下文)
     */
    SqlGenerationResult generate(String question, String schemaDigest,
                                 List<String> termsHit, List<FewshotStore.Example> fewshots,
                                 Long userId);

    /**
     * 错误自愈: 把上一次 SQL 与报错信息回喂模型重新生成。
     */
    SqlGenerationResult regenerate(String question, String schemaDigest,
                                   List<String> termsHit, List<FewshotStore.Example> fewshots,
                                   String previousSql, String errorMessage);
}

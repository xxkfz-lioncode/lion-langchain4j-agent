package com.lion.agent.classification;

/**
 * 情感分类标签。
 * <p>
 * 供两种分类实现共用:
 * <ul>
 *   <li>{@link SentimentAnalyzer}: 基于 LLM, 框架会自动把模型输出约束到本枚举值之一;</li>
 *   <li>{@link SentimentEmbeddingClassifier}: 基于向量相似度, 用本枚举作为分类标签。</li>
 * </ul>
 * 新增分类维度(如意图、实体识别)只需新建独立的枚举与对应的两个实现类即可, 模式可复用。
 */
public enum Sentiment {

    /** 正面 / 积极情绪 —— 满意、喜欢、推荐、夸奖等 */
    POSITIVE,

    /** 中性 / 中立情绪 —— 无明显好恶, 如"还行"、"一般般"、"凑合" */
    NEUTRAL,

    /** 负面 / 消极情绪 —— 失望、愤怒、厌恶、差评等 */
    NEGATIVE
}
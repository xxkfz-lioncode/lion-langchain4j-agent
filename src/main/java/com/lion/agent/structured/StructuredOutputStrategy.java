package com.lion.agent.structured;

/**
 * 结构化输出的三种方式(可靠性由高到低)。
 * <p>
 * 对应 LangChain4j 官方文档 "Structured Outputs" 里给出的三条路线, 本质区别在于
 * <b>把"输出必须长什么样"这件事交给谁</b>:
 * <ul>
 *   <li>{@link #JSON_SCHEMA} —— 交给模型侧: 请求里带 response_format=json_schema,
 *       由模型/供应商在解码阶段强制约束输出结构, 最可靠;</li>
 *   <li>{@link #JSON_MODE} —— 各退一步: 请求里只声明 response_format=json_object(保证是合法 JSON),
 *       字段名/类型这些结构信息靠提示词描述, 模型不一定完全遵守;</li>
 *   <li>{@link #PROMPT_ONLY} —— 全交给提示词: 请求里不设任何 response_format,
 *       模型很可能带上"好的, 结果如下:"之类的解释文字, 甚至漏字段。</li>
 * </ul>
 * <p>
 * 为什么还要保留后两种: 并非所有模型/供应商都支持 json_schema。官方列举的支持方包括
 * OpenAI、Azure OpenAI、Amazon Bedrock、Google AI Gemini、Mistral、Ollama 等;
 * 项目当前用的千问(DashScope OpenAI 兼容模式)对 json_object 支持良好,
 * 对 json_schema 则要看具体模型, 因此 {@link StructuredOutputService#extractWithFallback}
 * 提供了按本枚举顺序自动降级的能力。
 */
public enum StructuredOutputStrategy {

    /** 方式一: JSON Schema(最可靠) */
    JSON_SCHEMA("JSON Schema", "请求里声明 response_format=json_schema, 由模型侧保证输出符合 schema"),

    /** 方式二: 提示词 + JSON Mode */
    JSON_MODE("提示词 + JSON Mode", "提示词里描述字段 + response_format=json_object, 只保证是合法 JSON"),

    /** 方式三: 仅提示词(最不可靠) */
    PROMPT_ONLY("提示词", "结构约束全部写在提示词里, 模型可能带解释文字或漏字段");

    private final String label;
    private final String description;

    StructuredOutputStrategy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** 中文名, 用于日志与接口返回 */
    public String label() {
        return label;
    }

    /** 一句话说明本方式的约束边界 */
    public String description() {
        return description;
    }

    /** 是否还有更低一档的保底方式(PROMPT_ONLY 已是最低档) */
    public boolean hasFallback() {
        return this != PROMPT_ONLY;
    }

    /** 降级链: JSON_SCHEMA -> JSON_MODE -> PROMPT_ONLY; 最低档返回自身 */
    public StructuredOutputStrategy fallback() {
        return switch (this) {
            case JSON_SCHEMA -> JSON_MODE;
            case JSON_MODE, PROMPT_ONLY -> PROMPT_ONLY;
        };
    }
}

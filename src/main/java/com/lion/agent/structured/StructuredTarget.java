package com.lion.agent.structured;

import dev.langchain4j.model.chat.request.json.JsonSchema;

import java.util.Objects;

/**
 * 结构化输出的"目标类型"描述, 把三种方式要用的信息集中在一处。
 *
 * @param type       反序列化目标类型(record / POJO 均可, 字段名需与模型输出一致)
 * @param jsonSchema 方式一(JSON Schema)专用; 为 null 表示该类型只支持方式二/三
 * @param fieldGuide 方式二/三 专用: 提示词里对每个字段的中文说明 —— 没有 schema 可用时,
 *                   字段名/类型/取值范围只能靠这段文字告诉模型, 所以它必须写清楚
 * @param <T>        目标类型
 */
public record StructuredTarget<T>(Class<T> type, JsonSchema jsonSchema, String fieldGuide) {

    /** 紧凑构造器: 目标类型与字段说明是所有方式的必需项, 提前校验避免运行期才报错 */
    public StructuredTarget {
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(fieldGuide, "fieldGuide 不能为空");
    }

    /** 三种方式都能用的完整目标 */
    public static <T> StructuredTarget<T> of(Class<T> type, JsonSchema jsonSchema, String fieldGuide) {
        return new StructuredTarget<>(type, jsonSchema, fieldGuide);
    }

    /** 只有提示词的目标(方式二/三), 不声明 schema */
    public static <T> StructuredTarget<T> promptOnly(Class<T> type, String fieldGuide) {
        return new StructuredTarget<>(type, null, fieldGuide);
    }

    /** 是否声明了 JSON Schema(方式一的前提) */
    public boolean hasJsonSchema() {
        return jsonSchema != null;
    }

    /** 目标类型的简单名, 用于日志/异常信息 */
    public String typeName() {
        return type.getSimpleName();
    }
}

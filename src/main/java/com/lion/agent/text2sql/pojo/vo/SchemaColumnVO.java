package com.lion.agent.text2sql.pojo.vo;

import lombok.Data;

/**
 * 字段元数据出参。
 */
@Data
public class SchemaColumnVO {

    private Long id;

    private String tableName;

    private String columnName;

    private String dataType;

    /** 原始注释 */
    private String columnComment;

    /** LLM 增强后的业务含义 */
    private String llmDescription;

    /** 值域说明(枚举含义) */
    private String valueDomain;

    /** 采样值 */
    private String sampleValues;

    /** 键类型: PRI/UNI/MUL */
    private String columnKey;

    /** 是否敏感字段 */
    private Boolean sensitive;
}

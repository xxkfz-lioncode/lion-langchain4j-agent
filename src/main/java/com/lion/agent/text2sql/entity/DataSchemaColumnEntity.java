package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数字段元数据。
 */
@Data
@TableName("data_schema_column")
public class DataSchemaColumnEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 表名 */
    private String tableName;

    /** 字段名 */
    private String columnName;

    /** 数据类型 */
    private String dataType;

    /** 字段注释(原始) */
    private String columnComment;

    /** LLM 增强后的业务含义 */
    private String llmDescription;

    /** 是否可空: YES/NO */
    private String isNullable;

    /** 键类型: PRI/UNI/MUL */
    private String columnKey;

    /** 是否外键: 1 是 */
    private Integer isFk;

    /** 外键引用表 */
    private String refTable;

    /** 外键引用字段 */
    private String refColumn;

    /** 值域说明(枚举含义) */
    private String valueDomain;

    /** 采样值(top-N) */
    private String sampleValues;

    /** 是否敏感字段: 1 是(需脱敏) */
    private Integer isSensitive;

    /** 元数据版本 */
    private String schemaVersion;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

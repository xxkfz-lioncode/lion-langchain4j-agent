package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数表元数据(由 {@code SchemaManager} 扫描 information_schema 生成)。
 */
@Data
@TableName("data_schema_table")
public class DataSchemaTableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 表名 */
    private String tableName;

    /** 表注释(业务含义) */
    private String tableComment;

    /** 表类型 */
    private String tableType;

    /** 估算行数 */
    private Long rowCount;

    /** 是否允许问数查询: 1 允许 / 0 禁止(敏感表) */
    private Integer isQueryable;

    /** 元数据版本(全库刷新批次号) */
    private String schemaVersion;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

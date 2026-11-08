package com.lion.agent.text2sql.pojo.vo;

import lombok.Data;

/**
 * 表元数据出参(前端 Schema 导航用)。
 */
@Data
public class SchemaTableVO {

    private Long id;

    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String tableComment;

    /** 估算行数 */
    private Long rowCount;

    /** 是否允许查询 */
    private Boolean queryable;

    /** 字段列表(仅详情接口返回) */
    private java.util.List<SchemaColumnVO> columns;
}

package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务术语词典。
 */
@Data
@TableName("data_term")
public class DataTermEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 术语 */
    private String term;

    /** 同义词(逗号分隔) */
    private String aliases;

    /** 业务定义 */
    private String definition;

    /** 主表 */
    private String tableName;

    /** 主字段 */
    private String columnName;

    /** 计算口径(自然语言) */
    private String calcRule;

    /** 附加过滤条件(SQL 片段) */
    private String filterRule;

    /** 是否启用 */
    private Integer enabled;

    /** 创建人 */
    private String createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

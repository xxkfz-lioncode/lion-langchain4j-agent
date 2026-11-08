package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数脱敏规则。
 */
@Data
@TableName("data_mask_rule")
public class DataMaskRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 表名(* 表示所有表) */
    private String tableName;

    /** 字段名(* 表示所有字段) */
    private String columnName;

    /** 脱敏类型: PHONE/ID_CARD/EMAIL/NAME/BANK_CARD/CUSTOM */
    private String maskType;

    /** 自定义脱敏 SQL 表达式(仅 CUSTOM 用) */
    private String expression;

    /** 是否启用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

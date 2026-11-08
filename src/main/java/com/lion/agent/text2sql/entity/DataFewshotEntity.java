package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 问数 Few-shot 示例库。
 */
@Data
@TableName("data_fewshot")
public class DataFewshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标准问题 */
    private String question;

    /**
     * 标准 SQL。
     * <p>
     * 属性名必须叫 {@code sqlText} 而不是 {@code sql}: 列名是 {@code sql_text},
     * 若属性叫 {@code sql}, MyBatis-Plus 会生成 {@code sql_text AS sql},
     * 而 "SQL" 是 MySQL 8.0 保留字且 MP 不给别名加引号, 直接语法报错。
     * 属性名与列名对齐后就不会产生 AS 别名。
     */
    @TableField("sql_text")
    private String sqlText;

    /** 涉及的表(逗号分隔) */
    private String tables;

    /** 口径说明 */
    private String explanation;

    /** 平均评分(1~5) */
    private BigDecimal avgRating;

    /** 被注入 prompt 次数 */
    private Integer useCount;

    /** 是否启用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

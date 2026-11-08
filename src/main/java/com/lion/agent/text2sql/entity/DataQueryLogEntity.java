package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数日志(全链路留痕)。
 */
@Data
@TableName("data_query_log")
public class DataQueryLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 本次问数唯一id(串起召回/生成/执行各阶段) */
    private String bizId;

    /** 用户id */
    private Long userId;

    /** 用户原始问题 */
    private String question;

    /** 识别出的意图 */
    private String intent;

    /** 召回的表(逗号分隔) */
    private String recalledTables;

    /** 命中的术语(逗号分隔) */
    private String termsHit;

    /** 生成的 SQL(归一化后) */
    private String generatedSql;

    /** SQL 是否通过校验: 1 通过 / 0 拒绝 */
    private Integer sqlValid;

    /** 拒绝原因 */
    private String rejectReason;

    /** SQL 执行耗时(毫秒) */
    private Long executeMs;

    /** 返回行数 */
    private Integer rowCount;

    /** 结果状态: SUCCESS/EMPTY/TIMEOUT/ERROR */
    private String resultStatus;

    /** 错误信息 */
    private String errorMessage;

    /** 推断的图表类型 */
    private String chartType;

    /** 结果快照 JSON(脱敏后前 20 行: {columns:[], rows:[]}) */
    private String resultSnapshot;

    /** 使用的 schema 版本 */
    private String schemaVersion;

    private LocalDateTime createTime;
}

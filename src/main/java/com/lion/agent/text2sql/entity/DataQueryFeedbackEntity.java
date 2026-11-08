package com.lion.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问数用户反馈。
 */
@Data
@TableName("data_query_feedback")
public class DataQueryFeedbackEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 data_query_log.biz_id */
    private String bizId;

    /** 用户id */
    private Long userId;

    /** 评分: 1 赞 / -1 踩 / 0 未评价 */
    private Integer rating;

    /** 用户纠正后的 SQL */
    private String correctSql;

    /** 文字反馈 */
    private String comment;

    /** 处理状态: PENDING/HANDLED/IGNORED */
    private String handleStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

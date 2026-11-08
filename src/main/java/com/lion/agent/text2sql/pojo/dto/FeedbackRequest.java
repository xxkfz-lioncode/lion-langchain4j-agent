package com.lion.agent.text2sql.pojo.dto;

import lombok.Data;

/**
 * 问数反馈入参。
 */
@Data
public class FeedbackRequest {

    /** 关联本次问数的 bizId */
    private String bizId;

    /** 评分: 1 赞 / -1 踩 / 0 未评价 */
    private Integer rating;

    /** 用户纠正后的 SQL(踩时可选填) */
    private String correctSql;

    /** 文字反馈 */
    private String comment;
}

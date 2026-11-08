package com.lion.agent.text2sql.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待处理 bad case 出参。
 */
@Data
public class BadCaseVO {

    private Long id;

    private String bizId;

    private Long userId;

    /** 评分: 1 赞 / -1 踩 */
    private Integer rating;

    /** 用户纠正后的 SQL */
    private String correctSql;

    /** 文字反馈 */
    private String comment;

    /** 处理状态 */
    private String handleStatus;

    private LocalDateTime createTime;

    /** 关联的问数问题(来自 data_query_log) */
    private String question;

    /** 关联的生成 SQL */
    private String generatedSql;
}

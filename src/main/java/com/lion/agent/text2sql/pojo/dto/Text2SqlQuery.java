package com.lion.agent.text2sql.pojo.dto;

import lombok.Data;

/**
 * 问数请求入参。
 */
@Data
public class Text2SqlQuery {

    /** 用户自然语言问题(必填) */
    private String question;

    /** 会话id(可选, 用于多轮追问上下文) */
    private Long conversationId;

    /** 强制指定只读某张表(可选, 前端 Schema 导航点选后带入) */
    private String tableName;
}

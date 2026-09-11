package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话请求
 */
@Data
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 会话id(为空时后端自动新建) */
    private Long conversationId;
}

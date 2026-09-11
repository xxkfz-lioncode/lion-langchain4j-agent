package com.lion.agent.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RAG 知识库问答请求
 */
@Data
public class RagChatRequest {

    @NotBlank(message = "问题内容不能为空")
    private String question;
}

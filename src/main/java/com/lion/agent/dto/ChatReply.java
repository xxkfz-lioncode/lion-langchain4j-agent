package com.lion.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话回复
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatReply {

    /** AI 回复内容 */
    private String reply;
}

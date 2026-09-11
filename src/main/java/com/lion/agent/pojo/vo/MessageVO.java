package com.lion.agent.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话历史消息视图对象(仅展示用, 不含系统提示)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {

    /** 消息角色: user / assistant / tool */
    private String role;

    /** 消息文本 */
    private String content;
}

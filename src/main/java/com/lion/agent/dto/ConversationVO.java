package com.lion.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话视图对象(供会话列表 / 创建接口返回)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {

    /** 会话id */
    private Long id;

    /** 会话标题 */
    private String title;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

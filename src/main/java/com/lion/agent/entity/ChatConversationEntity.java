package com.lion.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体(一个用户可建多个会话)
 * <p>
 * 对应 chat_message.memory_id 形如 {@code chat:{userId}:{conversationId}},
 * 会话表仅承载元信息(标题 / 时间), 消息内容仍存 chat_message。
 */
@Data
@TableName("chat_conversation")
public class ChatConversationEntity {

    /** 会话id */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户id */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 创建时间(数据库自动维护) */
    private LocalDateTime createTime;

    /** 更新时间(数据库自动维护) */
    private LocalDateTime updateTime;
}

package com.lion.agent.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息明细实体(每条消息一行)
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    /** 主键(自增, 决定消息顺序) */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话记忆id(如 chat:1) */
    private String memoryId;

    /** 用户id(冗余自 memory_id, 便于按用户查询历史) */
    private Long userId;

    /** 消息角色: system/user/assistant/tool */
    private String role;

    /** 消息文本(展示用; 纯工具调用等可能为空) */
    private String content;

    /** 业务追踪id(一次提问一个, 与 llm_call_log.biz_id 关联; 写入时由 MySqlChatMemoryStore 从 LlmCallContext 盖章) */
    private String bizId;

    /** LangChain4j 消息完整 JSON(用于还原消息对象) */
    private String messageJson;

    /** 创建时间(插入时保持 null, 由数据库 DEFAULT CURRENT_TIMESTAMP 自动填充) */
    private LocalDateTime createTime;
}

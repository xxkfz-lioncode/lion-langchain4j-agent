package com.lion.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.entity.ChatMessageEntity;
import com.lion.agent.mapper.ChatMessageMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 的消息存储(LangChain4j ChatMemoryStore 实现)
 * <p>
 * 每条消息一行存入 chat_message 表, 携带 user_id / role 等冗余字段,
 * 便于按用户、按角色查询历史对话; message_json 保存消息完整 JSON,
 * 用于还原 LangChain4j 消息对象(含工具调用等复杂消息), 实现会话跨重启保留。
 */
@Slf4j
@Component
public class MySqlChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageMapper chatMessageMapper;

    public MySqlChatMemoryStore(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = String.valueOf(memoryId);
        List<ChatMessageEntity> rows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, key)
                        .orderByAsc(ChatMessageEntity::getId));
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (ChatMessageEntity row : rows) {
            try {
                messages.add(ChatMessageDeserializer.messageFromJson(row.getMessageJson()));
            } catch (RuntimeException e) {
                // 兼容性兜底: langchain4j 0.36(Gson) 升级到 1.x(Jackson) 后旧 message_json 可能无法解析,
                // 跳过该条历史记录, 下一次对话全量覆写时会自然清理掉
                log.warn("跳过无法解析的历史消息 id={}, memoryId={}, 原因: {}", row.getId(), key, e.getMessage());
            }
        }
        return messages;
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = String.valueOf(memoryId);
        Long userId = parseUserId(memoryId);
        // 窗口消息为全量最新状态, 先清后插保证一致性(单会话低频, 性能可接受)
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, key));
        for (ChatMessage message : messages) {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setMemoryId(key);
            entity.setUserId(userId);
            entity.setRole(roleOf(message));
            entity.setContent(contentOf(message));
            entity.setMessageJson(ChatMessageSerializer.messageToJson(message));
            chatMessageMapper.insert(entity);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, String.valueOf(memoryId)));
    }

    /** 从 memoryId 解析用户id(约定格式: chat:{userId} 或 chat:{userId}:{conversationId}), 非该格式返回 null */
    private Long parseUserId(Object memoryId) {
        String id = String.valueOf(memoryId);
        if (id.startsWith("chat:")) {
            String rest = id.substring("chat:".length());
            int colon = rest.indexOf(':');
            String userPart = colon >= 0 ? rest.substring(0, colon) : rest;
            try {
                return Long.valueOf(userPart);
            } catch (NumberFormatException ignored) {
                // 非数字, 返回 null
            }
        }
        return null;
    }

    /** 消息角色映射 */
    private String roleOf(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return "system";
        } else if (message instanceof UserMessage) {
            return "user";
        } else if (message instanceof AiMessage) {
            return "assistant";
        } else if (message instanceof ToolExecutionResultMessage) {
            return "tool";
        }
        return "unknown";
    }

    /** 提取消息文本用于展示(1.x 中 ChatMessage 无统一 text(), 需按类型取); 非单文本/无文本时返回 null */
    private String contentOf(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.hasSingleText() ? userMessage.singleText() : null;
        } else if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        } else if (message instanceof AiMessage aiMessage) {
            return aiMessage.text();
        } else if (message instanceof ToolExecutionResultMessage toolMessage) {
            try {
                return toolMessage.text();
            } catch (IllegalStateException e) {
                return null; // 多内容(非纯文本)消息
            }
        }
        return null;
    }
}

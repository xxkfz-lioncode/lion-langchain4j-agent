package com.lion.agent.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.ai.observability.LlmCallContext;
import com.lion.agent.pojo.entity.ChatMessageEntity;
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
        // 工具循环时本方法在 reactor 回调线程执行(此处必然持有 memoryId, 且与后续模型调用
        // 同线程): 把该会话的活跃上下文接回当前线程, 使本轮后续模型调用的 onRequest 能读到
        // 上下文(修复第 2+ 轮调用 llm_call_log 缺 bizId/userId), 也保证下面 biz_id 盖章正确
        LlmCallContext.rebind(key);
        // 重写前先取旧行: 用于按"位置+JSON相等"保留历史消息的 biz_id(见下方说明)
        List<ChatMessageEntity> oldRows = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, key)
                        .orderByAsc(ChatMessageEntity::getId));
        // 窗口消息为全量最新状态, 先清后插保证一致性(单会话低频, 性能可接受)
        chatMessageMapper.delete(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, key));

        // biz_id 盖章规则(方案B: 消息与 LLM 调用精确关联):
        // updateMessages 在一次提问期间会被多次全量重写(工具循环每加一条消息就重写一次),
        // 若直接用当前 ThreadLocal 的 bizId 盖所有行, 会把上一轮提问的历史章覆盖掉。
        // 因此: 与旧行"位置相同且 JSON 完全一致"的消息视为历史消息, 保留旧章;
        //      新增的消息(本轮新产生的)才盖当前调用的 bizId; system 消息不盖章。
        String currentBizId = LlmCallContext.get() == null ? null : LlmCallContext.get().bizId();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setMemoryId(key);
            entity.setUserId(userId);
            entity.setRole(roleOf(message));
            entity.setContent(contentOf(message));
            String json = ChatMessageSerializer.messageToJson(message);
            entity.setMessageJson(json);
            entity.setBizId(bizIdOf(message, i, oldRows, json, currentBizId));
            chatMessageMapper.insert(entity);
        }
    }

    /**
     * 决定一条消息的 biz_id:
     * <ul>
     *   <li>system 消息 → null(系统提示不属于任何一次提问);</li>
     *   <li>位置与旧行对应且 message_json 完全一致(消息一经加入记忆就不会变) → 保留旧 biz_id;</li>
     *   <li>其余(本轮新增的消息) → 当前调用上下文的 bizId, 无上下文时为 null。</li>
     * </ul>
     */
    private String bizIdOf(ChatMessage message, int index, List<ChatMessageEntity> oldRows,
                           String json, String currentBizId) {
        if (message instanceof SystemMessage) {
            return null;
        }
        if (index < oldRows.size()) {
            ChatMessageEntity old = oldRows.get(index);
            if (json.equals(old.getMessageJson())) {
                return old.getBizId();
            }
        }
        return currentBizId;
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

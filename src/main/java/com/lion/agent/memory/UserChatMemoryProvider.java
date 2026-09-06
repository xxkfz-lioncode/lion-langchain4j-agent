package com.lion.agent.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 memoryId 隔离的会话记忆提供者(基于 MySQL 持久化)
 * <p>
 * AiServices 每次对话都会通过该 Provider 获取 memoryId 对应的记忆实例,
 * 这里按 memoryId 缓存实例保证同用户多轮上下文不丢失;
 * 底层消息通过 {@link MySqlChatMemoryStore} 按行持久化到 chat_message 表,
 * 服务重启后自动恢复, 多实例部署时可共享会话。
 */
@Component
public class UserChatMemoryProvider implements ChatMemoryProvider {

    private final ChatMemoryStore chatMemoryStore;

    /** memoryId -> 聊天记忆(进程内窗口缓存, 内容与数据库同步) */
    private final Map<Object, MessageWindowChatMemory> memories = new ConcurrentHashMap<>();

    public UserChatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        this.chatMemoryStore = chatMemoryStore;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return memories.computeIfAbsent(memoryId,
                id -> MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build());
    }

    /** 清空某用户的会话记忆(移除内存缓存并删除数据库记录) */
    public void clear(Object memoryId) {
        memories.remove(memoryId);
        chatMemoryStore.deleteMessages(memoryId);
    }
}

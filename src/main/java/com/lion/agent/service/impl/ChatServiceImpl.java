package com.lion.agent.service.impl;

import com.lion.agent.assistant.AnnotatedAssistant;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.pojo.vo.MessageVO;
import com.lion.agent.memory.UserChatMemoryProvider;
import com.lion.agent.service.ChatService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话服务实现(基于 LangChain4j @AiService 注解式智能助手)
 * <p>
 * 说明:
 * 1. {@link AnnotatedAssistant} 接口标注了 @AiService, 由 langchain4j-spring-boot-starter
 *    自动扫描并生成代理 Bean(内部使用 AiServices 完成 模型 + 记忆 + 工具 的装配);
 * 2. 会话记忆由 {@link UserChatMemoryProvider} 按 memoryId(登录用户) 隔离,
 *    保留最近 20 条消息作为上下文, 底层持久化到 chat_message 表(MySQL),
 *    服务重启后自动恢复; 多实例部署时可将该 Provider 换成 Redis 实现。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final AnnotatedAssistant assistant;
    private final UserChatMemoryProvider memoryProvider;
    private final ChatMemoryStore chatMemoryStore;

    public ChatServiceImpl(AnnotatedAssistant assistant,
                           UserChatMemoryProvider memoryProvider,
                           ChatMemoryStore chatMemoryStore) {
        this.assistant = assistant;
        this.memoryProvider = memoryProvider;
        this.chatMemoryStore = chatMemoryStore;
    }

    @Override
    public String chat(String memoryId, String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            throw new BusinessException("消息内容不能为空");
        }
        // 注解式调用: 框架自动完成系统提示渲染、消息入记忆、工具调用、AI 回复入记忆
        return assistant.chat(memoryId, userMessage);
    }

    @Override
    public TokenStream chatStream(String memoryId, String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            throw new BusinessException("消息内容不能为空");
        }
        // 返回 TokenStream, 由控制器注册回调后 start(), 千问逐 token 推送
        return assistant.chatStream(memoryId, userMessage);
    }

    @Override
    public void clear(String memoryId) {
        memoryProvider.clear(memoryId);
    }

    @Override
    public List<MessageVO> listMessages(String memoryId) {
        List<MessageVO> list = new ArrayList<>();
        for (ChatMessage message : chatMemoryStore.getMessages(memoryId)) {
            if (message instanceof SystemMessage) {
                continue; // 系统提示不展示
            }
            list.add(new MessageVO(roleOf(message), textOf(message)));
        }
        return list;
    }

    /** 提取消息纯文本用于展示(与 MySqlChatMemoryStore.contentOf 保持一致); 非单文本/无文本时返回 null */
    private String textOf(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.hasSingleText() ? userMessage.singleText() : null;
        } else if (message instanceof AiMessage aiMessage) {
            return aiMessage.text();
        } else if (message instanceof ToolExecutionResultMessage toolMessage) {
            try {
                return toolMessage.text();
            } catch (IllegalStateException e) {
                return null; // 多内容(非纯文本)消息, 不用于展示
            }
        }
        return null;
    }

    /** 消息角色映射(与 MySqlChatMemoryStore.roleOf 保持一致) */
    private String roleOf(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "user";
        } else if (message instanceof AiMessage) {
            return "assistant";
        } else if (message instanceof ToolExecutionResultMessage) {
            return "tool";
        }
        return "unknown";
    }
}

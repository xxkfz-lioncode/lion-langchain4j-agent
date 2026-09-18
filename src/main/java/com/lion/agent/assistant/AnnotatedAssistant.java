package com.lion.agent.assistant;

import com.lion.agent.guardrail.LoggingInputGuardrail;
import com.lion.agent.guardrail.LoggingOutputGuardrail;
import com.lion.agent.memory.UserChatMemoryProvider;
import com.lion.agent.tools.DateTools;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 智能助手(注解式声明, 由 langchain4j-spring-boot-starter 自动生成代理 Bean)
 * <p>
 * 装配说明(1.x 需显式 wiringMode=EXPLICIT 才会按下列 bean 名装配):
 * 1. chatModel / streamingChatModel 引用 langchain4j-open-ai-spring-boot-starter
 *    自动装配的模型 Bean(openAiChatModel / openAiStreamingChatModel);
 * 2. chatMemoryProvider 引用 {@link UserChatMemoryProvider}, 按 memoryId(登录用户)隔离会话记忆,
 *    clear() 时调用 provider 移除对应记忆;
 * 3. tools 引用 {@link DateTools}(该类注册为 Spring Bean, 含 @Tool 方法)。
 * <p>
 * 关于阻塞 / 流式: AiServices 依据方法返回类型路由模型——
 * 返回 {@code String} 走 chatModel(整段返回), 返回 {@link TokenStream} 走
 * streamingChatModel(逐 token 推送)。因此提供 chat 与 chatStream 两个方法。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "userChatMemoryProvider",
        // 多个工具直接在数组中用逗号隔开，传入对应的 Bean 名称
        tools = {"dateTools","weatherTools"}
)
// 注解式护栏: 仅传 Class(框架用反射 new, 不能依赖 Spring Bean), 触发时机见对应类的 javadoc
@InputGuardrails({LoggingInputGuardrail.class})
@OutputGuardrails({LoggingOutputGuardrail.class})
public interface AnnotatedAssistant {

    /**
     * 阻塞对话: 等千问整段生成完再返回
     *
     * @param memoryId 会话记忆 id(通常是 "chat:" + 登录用户id)
     * @param message  用户消息
     * @return AI 回复全文
     */
    @SystemMessage(fromResource = "prompts/agent-system.txt")
    String chat(@MemoryId String memoryId, @UserMessage("{{message}}") String message);

    /**
     * 流式对话: 返回 {@link TokenStream}, 调用方注册 onPartialResponse 等回调后 start(),
     * 千问每个 token 生成后即回调, 实现打字机效果。
     *
     * @param memoryId 会话记忆 id(通常是 "chat:" + 登录用户id)
     * @param message  用户消息
     * @return TokenStream(需调用方消费)
     */
    @SystemMessage(fromResource = "prompts/agent-system.txt")
    TokenStream chatStream(@MemoryId String memoryId, @UserMessage("{{message}}") String message);
}

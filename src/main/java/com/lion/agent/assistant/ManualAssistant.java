package com.lion.agent.assistant;

import com.lion.agent.skills.SkillsFactory;
import com.lion.agent.tools.programmatic.ProgrammaticToolsFactory;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.skills.Skills;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 编程式(手工)装配的智能助手: 用 AiServices 显式注册 ToolSpecification + ToolExecutor。
 * <p>
 * 与 {@link AnnotatedAssistant} 的对比:
 * <ul>
 *   <li>注解式: 接口标 {@code @AiService}, 工具类标 {@code @Tool}, starter 自动扫描并生成代理 Bean,
 *       优点是零代码, 缺点是工具描述/参数由注解写死, 运行时不能动态增删;</li>
 *   <li>编程式(本类): 通过 {@link ProgrammaticToolsFactory} 拿到 spec + executor 映射,
 *       通过 {@link SkillsFactory} 拿到 Skills, 通过可选的 {@link McpToolProvider} 拿到
 *       MCP 子进程工具, 一次性注入 {@code AiServices}。
 *       可按开关/配置/租户动态决定暴露哪些工具, 也便于给每个工具加日志、限流、灰度等横切逻辑。</li>
 * </ul>
 * <p>
 * <b>三套工具的合并方式</b>:
 * <ul>
 *   <li>编程式静态工具 → {@code .tools(spec -> executor 映射)};</li>
 *   <li>Skills 动态工具 → {@code .toolProvider(skills.toolProvider())};</li>
 *   <li>MCP 动态工具 → 与 Skills 一起通过 {@code .toolProviders(skills, mcp)} 合并注入
 *       (变参 API, 内部把多个 Provider 的结果取并集)。</li>
 * </ul>
 * 注意: AiServices 只能为<b>接口</b>(或抽象类)生成代理, 所以本类内部定义了
 * {@link ProgrammaticAssistant} 接口, 本类只负责"装配 + 持有 + 对外转发"。
 * 工具本身集中在 {@code com.lion.agent.tools.programmatic} /
 * {@code com.lion.agent.skills} / {@code com.lion.agent.mcp} 包下, 本类不写任何工具实现细节。
 */
@Slf4j
@Component
public class ManualAssistant {

    /**
     * 编程式助手接口(不是 Spring Bean, 由 {@link AiServices#builder} 生成实现)。
     * 方法上的 @SystemMessage / @UserMessage / @MemoryId 与注解式用法完全一致, 由 AiServices 解析。
     */
    public interface ProgrammaticAssistant {

        /** 阻塞对话: 等模型整段生成完再返回 */
        @SystemMessage(fromResource = "prompts/agent-system.txt")
        String chat(@MemoryId String memoryId, @UserMessage("{{message}}") String message);

        /** 流式对话: 返回 TokenStream, 调用方注册回调后 start() */
        @SystemMessage(fromResource = "prompts/agent-system.txt")
        TokenStream chatStream(@MemoryId String memoryId, @UserMessage("{{message}}") String message);
    }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final ProgrammaticToolsFactory programmaticToolsFactory;
    private final SkillsFactory skillsFactory;
    private final ContentRetriever contentRetriever;
    /**
     * MCP 工具提供者(可空 —— 由 {@code McpClientConfig} 上的
     * {@code @ConditionalOnProperty(lion.mcp.enabled=true)} 控制是否装配;
     * 关闭 MCP 时本字段为 {@code null}, {@link #init()} 会跳过 MCP 接入)
     */
    private final McpToolProvider mcpToolProvider;

    /** 手工装配出来的助手代理, 等价于 @AiService 生成的 Bean */
    private ProgrammaticAssistant assistant;

    public ManualAssistant(@Qualifier("openAiChatModel") ChatModel chatModel,
                           @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
                           ChatMemoryProvider chatMemoryProvider,
                           ProgrammaticToolsFactory programmaticToolsFactory,
                           SkillsFactory skillsFactory,
                           ContentRetriever contentRetriever,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           McpToolProvider mcpToolProvider) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.programmaticToolsFactory = programmaticToolsFactory;
        this.skillsFactory = skillsFactory;
        this.contentRetriever = contentRetriever;
        this.mcpToolProvider = mcpToolProvider;
    }

    /**
     * 装配时机: 用 @PostConstruct 而不是构造函数, 是为了让所有依赖(ChatModel / 记忆 / 工具依赖)都注入完成后再 build。
     * AiServices.build() 只是生成代理, 不会发起任何模型请求, 启动开销可忽略。
     * <p>
     * 装配时直接从工厂拿现成的 spec -> executor 映射, 本类不写任何工具定义。
     */
    @PostConstruct
    public void init() {
        // 编程式静态工具(由 ProgrammaticToolsFactory 集中管理)
        Map<ToolSpecification, ToolExecutor> tools = programmaticToolsFactory.getTools();
        // Skills 装配(classpath + 自定义 + 工具配置)集中在 SkillsFactory, 本类只取结果
        Skills skills = skillsFactory.getSkills();

        // 构建 AI Service
        AiServices<ProgrammaticAssistant> builder = AiServices.builder(ProgrammaticAssistant.class)
                .chatModel(chatModel)                    // 阻塞对话用
                .streamingChatModel(streamingChatModel)  // chatStream 用
                .chatMemoryProvider(chatMemoryProvider)  // 按 memoryId(登录用户)隔离多轮上下文
                .tools(tools)                            // 编程式注册工具: spec -> executor
                // 启用RAG
                .contentRetriever(contentRetriever);

        // 合并 Skills + MCP 两个动态 ToolProvider
        // - toolProviders(...) 是变参, 内部把多个 Provider 的 provideTools 结果拼成一组工具
        // - MCP 子进程关停(lion.mcp.enabled=false)时 mcpToolProvider 为 null, 此时只挂 Skills
        if (mcpToolProvider != null) {
            builder.toolProviders(skills.toolProvider(), mcpToolProvider);
            log.info("[编程式助手] 已合并 Skills + MCP 两套动态工具");
        } else {
            builder.toolProvider(skills.toolProvider());
            log.info("[编程式助手] 仅 Skills 工具(MCP 未启用)");
        }

        this.assistant = builder.build();

        log.info("[编程式助手] 装配完成, 已注册静态工具: {}", tools.keySet().stream().map(ToolSpecification::name).toList());
    }

    /** 阻塞对话(对外入口, 供 Service/Controller 调用) */
    public String chat(String memoryId, String message) {
        return assistant.chat(memoryId, message);
    }

    /** 流式对话(对外入口, 供 Service/Controller 调用) */
    public TokenStream chatStream(String memoryId, String message) {
        return assistant.chatStream(memoryId, message);
    }
}
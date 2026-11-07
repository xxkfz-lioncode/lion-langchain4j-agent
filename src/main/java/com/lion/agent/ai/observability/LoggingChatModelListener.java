package com.lion.agent.ai.observability;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 聊天模型可观测性监听器: 记录每次对 LLM 的请求 / 响应 / 错误(含 Token 用量)。
 * <p>
 * <b>接入方式(零装配)</b>: langchain4j-open-ai-spring-boot-starter 会自动收集容器中所有
 * {@code ChatModelListener} Bean, 统一挂到自动装配的 {@code openAiChatModel} /
 * {@code openAiStreamingChatModel} 上(见其 AutoConfig 源码中的
 * {@code ObjectProvider<ChatModelListener> listeners})。因此本类只需标 {@code @Component}。
 * <p>
 * 双通道记录:
 * <ul>
 *   <li>控制台日志 —— info 摘要(模型/Token/结束原因) + debug 完整消息, 便于开发排查;</li>
 *   <li>落库 llm_call_log —— 异步写入审计表, 供前端"LLM 调用日志"页面查询。</li>
 * </ul>
 * <p>
 * 业务上下文透传(userId / memoryId / bizId):{@code onRequest} 与业务同线程, 从
 * {@link LlmCallContext} 读出后转存 request attributes —— attributes 会自动带到
 * {@code onResponse} / {@code onError} 回调(此时可能已在 reactor 线程, ThreadLocal 失效)。
 * <p>
 * 落库铁律(不影响主流程):
 * <ul>
 *   <li>独立单线程池异步写, 回调立即返回, 绝不在回调线程上同步写库;</li>
 *   <li>有界队列(1000), 满了丢弃最旧并告警, 日志丢了也不能拖垮对话;</li>
 *   <li>落库异常只 warn, 不向上抛。</li>
 * </ul>
 * 事件属性遵循 OpenTelemetry Generative AI 语义约定, 后续接 OTel / Langfuse 时
 * 替换 saveAsync 的实现即可。
 */
@Slf4j
@Component
public class LoggingChatModelListener implements ChatModelListener {

    /** attributes key: 业务上下文(onRequest 存入, onResponse/onError 取出) */
    private static final String ATTR_CONTEXT = "llm.call.context";
    /** attributes key: 请求发出时间戳(用于计算耗时) */
    private static final String ATTR_START_TIME = "llm.call.startTime";

    private final LlmCallLogMapper llmCallLogMapper;

    /**
     * 日志落库专用单线程池: 与业务线程隔离; 队列满时丢弃最旧任务(DiscardOldestPolicy),
     * 保证高并发下不积压、不阻塞对话主流程。
     */
    private final ThreadPoolExecutor logExecutor = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "llm-call-log");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public LoggingChatModelListener(LlmCallLogMapper llmCallLogMapper) {
        this.llmCallLogMapper = llmCallLogMapper;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ChatRequest request = requestContext.chatRequest();
        List<ChatMessage> messages = request.messages();
        log.info("[LLM请求] model={}, temperature={}, topP={}, maxOutputTokens={}, 消息数={}, 工具数={}",
                request.parameters().modelName(),
                request.parameters().temperature(),
                request.parameters().topP(),
                request.parameters().maxOutputTokens(),
                messages.size(),
                request.parameters().toolSpecifications() == null
                        ? 0 : request.parameters().toolSpecifications().size());
        // 完整消息(含系统提示/记忆/工具调用消息)可能很长, 放 debug
        log.debug("[LLM请求-完整消息] {}", messages);

        // 业务上下文透传: ThreadLocal(业务线程) -> attributes(跨线程带到响应/错误回调)
        LlmCallContext.Context ctx = LlmCallContext.get();
        if (ctx != null) {
            requestContext.attributes().put(ATTR_CONTEXT, ctx);
        }
        requestContext.attributes().put(ATTR_START_TIME, System.currentTimeMillis());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        ChatResponse response = responseContext.chatResponse();
        // metadata()/tokenUsage() 均可能为 null(部分供应商不返回), 统一做空安全处理
        var metadata = response.metadata();
        TokenUsage usage = metadata == null ? null : metadata.tokenUsage();
        log.info("[LLM响应] model={}, finishReason={}, token(输入/输出/总计)={}/{}/{}",
                metadata == null ? "?" : metadata.modelName(),
                metadata == null ? "?" : metadata.finishReason(),
                usage == null ? -1 : usage.inputTokenCount(),
                usage == null ? -1 : usage.outputTokenCount(),
                usage == null ? -1 : usage.totalTokenCount());
        log.debug("[LLM响应-完整消息] {}", response.aiMessage());

        saveAsync(buildSuccessLog(responseContext));
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.error("[LLM错误] {}", errorContext.error().getMessage(), errorContext.error());

        saveAsync(buildErrorLog(errorContext));
    }

    // ==================== 落库 ====================

    /** 成功记录: 模型名/Token/结束原因来自响应, 耗时与上下文来自 attributes */
    private LlmCallLog buildSuccessLog(ChatModelResponseContext responseContext) {
        var metadata = responseContext.chatResponse().metadata();
        TokenUsage usage = metadata == null ? null : metadata.tokenUsage();

        LlmCallLog row = baseLog(responseContext.attributes());
        row.setSuccess(1);
        if (metadata != null) {
            row.setModel(metadata.modelName());
            row.setFinishReason(metadata.finishReason() == null ? null : metadata.finishReason().name());
        }
        if (usage != null) {
            row.setInputTokens(usage.inputTokenCount());
            row.setOutputTokens(usage.outputTokenCount());
            row.setTotalTokens(usage.totalTokenCount());
        }
        return row;
    }

    /** 失败记录: 成功字段置 0, 记录错误摘要 */
    private LlmCallLog buildErrorLog(ChatModelErrorContext errorContext) {
        LlmCallLog row = baseLog(errorContext.attributes());
        row.setSuccess(0);
        String message = errorContext.error().getMessage();
        row.setErrorMessage(message == null ? errorContext.error().getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 500)));
        return row;
    }

    /** 公共字段: 上下文(userId/memoryId/bizId) + 耗时(onRequest 存的时间戳) */
    private LlmCallLog baseLog(Map<Object, Object> attributes) {
        LlmCallLog row = new LlmCallLog();
        Object ctx = attributes.get(ATTR_CONTEXT);
        if (ctx instanceof LlmCallContext.Context c) {
            row.setBizId(c.bizId());
            row.setMemoryId(c.memoryId());
            row.setUserId(c.userId());
        }
        Object start = attributes.get(ATTR_START_TIME);
        if (start instanceof Long startTime) {
            row.setDurationMs(System.currentTimeMillis() - startTime);
        }
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    /** 异步落库: 提交到单线程池立即返回; 任何异常只 warn 不抛, 保证不拖垮对话链路 */
    private void saveAsync(LlmCallLog row) {
        try {
            logExecutor.execute(() -> {
                try {
                    llmCallLogMapper.insert(row);
                } catch (Exception e) {
                    log.warn("[LLM日志落库失败] {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            // 队列满被拒绝等场景: 只告警, 不影响主流程
            log.warn("[LLM日志落库被丢弃] bizId={}", row.getBizId());
        }
    }
}

package com.lion.agent.ai.observability;

import java.util.UUID;

/**
 * LLM 调用日志的业务上下文(ThreadLocal 透传)。
 * <p>
 * {@link LoggingChatModelListener} 的回调里只有模型请求参数, 拿不到 userId / memoryId
 * 等业务信息。解决方式: 业务层调用模型前把上下文放入 ThreadLocal(本类), 监听器的
 * {@code onRequest} 回调(与业务线程相同)取出后转存到 request attributes ——
 * attributes 会随请求一路带到 {@code onResponse} / {@code onError}(此时可能已切到
 * reactor 线程, ThreadLocal 读不到), 完成业务上下文的跨线程透传。
 * <p>
 * 使用约定:
 * <ul>
 *   <li>阻塞调用: try/finally 中 begin / clear;</li>
 *   <li>流式调用: begin 后不清(模型请求在 {@code TokenStream.start()} 时才真正发出,
 *       且在当前请求线程执行), 由 {@link LlmObservabilityWebConfig} 的拦截器在
 *       请求结束后统一 clear, 防止 Tomcat 线程复用导致上下文串号。</li>
 * </ul>
 */
public final class LlmCallContext {

    /** 一次用户提问的业务上下文; bizId 串起该次提问触发的多轮模型调用(工具循环) */
    public record Context(Long userId, String memoryId, String bizId) {}

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private LlmCallContext() {
    }

    /**
     * 开启一次调用上下文。
     *
     * @param memoryId 会话记忆 id(形如 chat:{userId}:{conversationId}, 据此解析 userId)
     * @return 本次调用的 bizId(写入日志表的追踪键)
     */
    public static String begin(String memoryId) {
        Long userId = null;
        if (memoryId != null && memoryId.startsWith("chat:")) {
            try {
                userId = Long.valueOf(memoryId.split(":")[1]);
            } catch (RuntimeException ignore) {
                // 非 chat: 前缀的 memoryId(如联调测试), userId 置空
            }
        }
        String bizId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        HOLDER.set(new Context(userId, memoryId, bizId));
        return bizId;
    }

    /** 当前线程的上下文; 未设置(如非对话链路的模型调用)返回 null */
    public static Context get() {
        return HOLDER.get();
    }

    /** 清理上下文(必须调用, 否则线程池复用会读到脏数据) */
    public static void clear() {
        HOLDER.remove();
    }
}

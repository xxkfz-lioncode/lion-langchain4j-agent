package com.lion.agent.ai.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 活跃上下文注册表(memoryId -> Context)。
     * <p>
     * 为什么需要: 流式对话的工具循环 continuation(工具执行 -> 后续模型调用)跑在
     * reactor 线程池上, ThreadLocal 只对发起请求的 Tomcat 线程可见 —— 只靠 ThreadLocal
     * 的话, 第 2 轮及以后的模型调用拿不到上下文(表现为 llm_call_log 的 bizId/user_id
     * 为空, chat_message 盖章为 null)。以 memoryId 为键注册后, 任何线程都能通过
     * {@link #rebind} 把上下文"接回"自己线程(memoryId 在回调链路中总是可得)。
     * <p>
     * 局限: 同一会话并发提问会互相覆盖(后问的赢), 教学项目可接受; 生产应改用
     * 请求级透传(如把上下文编码进模型请求的自定义 header / attributes 链)。
     */
    private static final Map<String, Context> ACTIVE = new ConcurrentHashMap<>();

    private LlmCallContext() {
    }

    /**
     * 开启一次调用上下文(业务层在调用模型前调用; 同时注册到活跃表供跨线程找回)。
     *
     * @param memoryId 会话记忆 id(形如 chat:{userId}:{conversationId}, 据此解析 userId)
     * @return 本次调用的 bizId(写入日志表的追踪键)
     */
    public static String begin(String memoryId) {
        Context ctx = of(memoryId);
        HOLDER.set(ctx);
        if (memoryId != null) {
            ACTIVE.put(memoryId, ctx);
        }
        return ctx.bizId();
    }

    /** 当前线程的上下文; 未设置(如非对话链路的模型调用)返回 null */
    public static Context get() {
        return HOLDER.get();
    }

    /**
     * 把 memoryId 对应的活跃上下文"接回"当前线程(ThreadLocal 为空或属于别的会话时才接)。
     * <p>
     * 调用时机: 工具循环中 {@code MySqlChatMemoryStore.updateMessages(memoryId, ...)}
     * 写消息时 —— 此处必然持有 memoryId, 且与后续模型调用同线程, 使下一轮
     * onRequest 能读到上下文(同时修复 chat_message 的 biz_id 盖章)。
     */
    public static void rebind(String memoryId) {
        if (memoryId == null) {
            return;
        }
        Context current = HOLDER.get();
        if (current != null && memoryId.equals(current.memoryId())) {
            return; // 本线程已是该会话的上下文, 无需接回
        }
        Context ctx = ACTIVE.get(memoryId);
        if (ctx != null) {
            HOLDER.set(ctx);
        }
    }

    /** 仅清理当前线程的上下文(监听器 onResponse/onError 收尾时调用, 防止 reactor 线程池污染) */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 结束一次调用: 清当前线程 + 从活跃表注销(阻塞式调用 finally 中调用)。
     * 流式调用的注销由拦截器在请求结束时调用 {@link #finishCurrent}。
     */
    public static void end(String memoryId) {
        HOLDER.remove();
        if (memoryId != null) {
            ACTIVE.remove(memoryId);
        }
    }

    /** 请求收尾(拦截器 afterCompletion 调用): 按当前线程上下文的 memoryId 注销活跃表并清线程 */
    public static void finishCurrent() {
        Context ctx = HOLDER.get();
        if (ctx != null && ctx.memoryId() != null) {
            ACTIVE.remove(ctx.memoryId());
        }
        HOLDER.remove();
    }

    /** 解析 memoryId 构造上下文(userId 从 chat:{userId}:... 解析, 解析失败为 null) */
    private static Context of(String memoryId) {
        Long userId = null;
        if (memoryId != null && memoryId.startsWith("chat:")) {
            try {
                userId = Long.valueOf(memoryId.split(":")[1]);
            } catch (RuntimeException ignore) {
                // 非 chat: 前缀的 memoryId(如联调测试), userId 置空
            }
        }
        String bizId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new Context(userId, memoryId, bizId);
    }
}

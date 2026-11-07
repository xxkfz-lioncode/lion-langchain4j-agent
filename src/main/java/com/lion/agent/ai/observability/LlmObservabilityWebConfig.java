package com.lion.agent.ai.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 可观测性 Web 配置: 注册 {@link LlmCallContext} 清理拦截器。
 * <p>
 * 为什么需要: 流式对话的 {@code TokenStream.start()} 由控制器线程触发模型请求,
 * ThreadLocal 上下文在 {@code ChatServiceImpl.chatStream} 中设置后无法就地清理
 * (流何时结束只有回调知道, 而回调线程的 ThreadLocal 与请求线程不是同一个)。
 * <p>
 * 因此统一由本拦截器在请求结束时清理:
 * <ul>
 *   <li>{@code afterCompletion} —— 普通请求响应后 / SSE 异步请求完成后触发, 清理上下文;</li>
 *   <li>{@code afterConcurrentHandlingStarted} —— 异步请求(阻塞式对话实际为同步, SSE 为异步)
 *       起始线程结束时再兜底清理一次, 确保线程池复用不会读到脏上下文。</li>
 * </ul>
 */
@Configuration
public class LlmObservabilityWebConfig implements WebMvcConfigurer {

    /** 仅做上下文清理, 无任何鉴权/放行逻辑 */
    private static final HandlerInterceptor CLEAR_CONTEXT_INTERCEPTOR = new HandlerInterceptor() {

        @Override
        public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull Object handler, @Nullable Exception ex) {
            // 流式调用: 请求线程的 ThreadLocal 从 chatStream 的 begin 一直保留到这里,
            // 据其 memoryId 注销活跃上下文注册表并清线程; 阻塞调用已在业务层 finally 中注销
            LlmCallContext.finishCurrent();
        }
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(CLEAR_CONTEXT_INTERCEPTOR).addPathPatterns("/**");
    }
}

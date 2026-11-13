package com.lion.agent.ai.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 异步任务配置: 文档解析(切分+向量化)为长耗时任务, 独立线程池执行,
 * 不占用 Web 请求线程(避免大文档解析拖垮 HTTP 线程池/触发前端超时)
 * <p>
 * 线程数按 DashScope embedding 限流约束保守设定: 并发批处理请求过多会触发限流,
 * 核心数 2 / 最大 4 / 队列 100 已够学习项目使用, 生产应结合 embedding 配额压测调整。
 */
@Slf4j
@EnableAsync
@Configuration
public class RagAsyncConfig {

    /**
     * 解析任务专用线程池(@Async("ragParseExecutor") 引用):
     * 拒绝策略 AbortPolicy(队列满直接抛异常, 由触发接口把"系统繁忙"返回给用户, 不静默吞任务)
     */
    @Bean("ragParseExecutor")
    public ThreadPoolTaskExecutor ragParseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 应用关闭时等待进行中的批次写完(最多 30s), 避免半途截断造成向量库残留
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("RAG 解析线程池已初始化: core=2, max=4, queue=100");
        return executor;
    }
}

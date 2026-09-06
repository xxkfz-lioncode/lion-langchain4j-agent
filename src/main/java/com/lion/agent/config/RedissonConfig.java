package com.lion.agent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置
 * <p>
 * Redis 连接信息来自 .env(如 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_DATABASE),
 * 后续可用于分布式锁、分布式限流、分布式会话等场景。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${REDIS_HOST:127.0.0.1}") String host,
            @Value("${REDIS_PORT:6379}") int port,
            @Value("${REDIS_PASSWORD:}") String password,
            @Value("${REDIS_DATABASE:0}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

package com.lion.agent.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 持久化配置: 将默认的内存 SaTokenDao 切换为 Redisson(Redis) 实现。
 * <p>
 * 效果: 登录态写入 Redis, 后端重启 / 多实例部署时 token 依然有效,
 * 避免“重启后端前端就退出登录”的问题。RedissonClient 复用 {@link RedissonConfig} 中
 * 已有的实例, Redis 连接信息来自 .env(REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DATABASE)。
 */
@Configuration
public class SaTokenRedissonConfig {

    @Bean
    public SaTokenDao saTokenDao(RedissonClient redissonClient) {
        return new SaTokenDaoForRedisson(redissonClient);
    }
}

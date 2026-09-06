package com.lion.agent.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置
 * <p>
 * 拦截所有 /api/** 请求(除登录接口外), 校验是否登录。
 * 登录态已由 {@link SaTokenRedissonConfig} 持久化到 Redis(复用项目 RedissonClient),
 * 后端重启后 token 依然有效, 支持多实例共享。
 * <p>
 * 免登录放行: 位于 {@code com.lion.agent.controller.test} 包下的控制器全部免登录,
 * 仅用于联调/测试(包内请勿使用 @SaCheckLogin/@SaCheckRole 等注解)。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /** 测试控制器所在包: 该包下所有接口免登录 */
    private static final String TEST_PKG = "com.lion.agent.controller.test";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // controller.test 包下接口直接放行, 不做登录校验
            if (handle instanceof HandlerMethod hm
                    && TEST_PKG.equals(hm.getBeanType().getPackageName())) {
                return;
            }
            StpUtil.checkLogin();
        }))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}

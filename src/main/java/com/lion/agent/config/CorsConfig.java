package com.lion.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置(开发期放开)
 * <p>
 * 开发环境前端默认走 Vite 代理, 无需跨域;
 * 若前端直连后端(修改了 VITE_API_BASE_URL), 则依赖此配置。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 开发期允许的来源 */
    private static final String[] ALLOWED_ORIGIN_PATTERNS = {"http://localhost:*", "http://127.0.0.1:*"};

    /**
     * 需要放行跨域的前缀
     * <p>
     * /api     — 业务接口
     * /test    — 联调测试接口(免登录, 供控制台与「接口文档」页在线调试)
     * /weather — 天气工具演示接口(同上)
     * <p>
     * 这几个前缀都要放行: 只写 /api/** 时, 在「接口文档」页(5173)里点 Try it out
     * 调 /test、/weather 会因浏览器 CORS 拦截而报 "Failed to fetch"。
     */
    private static final String[] CORS_MAPPINGS = {"/api/**", "/test/**", "/weather"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String mapping : CORS_MAPPINGS) {
            registry.addMapping(mapping)
                    .allowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .exposedHeaders("satoken")
                    .maxAge(3600);
        }
    }
}

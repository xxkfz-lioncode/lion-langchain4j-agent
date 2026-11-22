package com.lion.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 单页应用路由回退(Vue Router history 模式):
 * 前端构建产物放进 classpath:/static 与后端同 jar 启动后, 用户刷新 /rag、/vector 等
 * 前端路由路径时, 服务器上并不存在 rag/ 这个真实文件, 默认响应 404 ——
 * 这里把「非 API 且无扩展名」的请求统一回退到 index.html, 交给前端路由接管。
 * <p>
 * 带 . 扩展名的请求(js/css/图片等静态资源)找不到就正常 404, 不误回退成 HTML;
 * /api/** 找不到也返回 404(接口 404 不能被吞成页面)。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 1. 真实静态资源存在 → 直接返回
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 2. API 与带扩展名的资源请求不回退, 保持真实 404
                        if (resourcePath.startsWith("api/") || resourcePath.contains(".")) {
                            return null;
                        }
                        // 3. 其余路径回退到 index.html(SPA 前端路由接管)
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }
}

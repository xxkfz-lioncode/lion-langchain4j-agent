package com.lion.agent;

import com.lion.agent.config.EnvFileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Lion LangChain4j Agent 启动类
 * <p>
 * 说明: 启动前先加载项目根目录 .env 文件为系统属性,
 * 这样 application.yml 中的 ${...} 占位符即可引用 .env 中的敏感配置,
 * 敏感信息不写入 yml, 且 .env 已加入 .gitignore。
 */
@Slf4j
@SpringBootApplication
public class LionAgentApplication {

    /** 启动时间(用于在就绪监听器中计算启动耗时) */
    private static final long START_TIME = System.currentTimeMillis();

    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(LionAgentApplication.class, args);
    }

    /**
     * 应用就绪(ApplicationReadyEvent)后打印启动信息: 端口 / 访问地址 / 模型等
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> startupInfo() {
        return event -> {
            Environment env = event.getApplicationContext().getEnvironment();
            String appName = env.getProperty("spring.application.name", "lion-langchain4j-agent");
            String port = env.getProperty("server.port", "8080");
            String contextPath = env.getProperty("server.servlet.context-path", "");
            String baseUrl = "http://localhost:" + port + contextPath;
            String modelName = env.getProperty(
                    "langchain4j.open-ai.chat-model.model-name", "qwen-plus");

            log.info("==============================================================");
            log.info("  {} 启动成功!", appName);
            log.info("--------------------------------------------------------------");
            log.info("  启动耗时    : {} ms", System.currentTimeMillis() - START_TIME);
            log.info("  后端地址    : {}", baseUrl);
            log.info("  当前模型    : {} (DashScope 千问)", modelName);
            log.info("  在线文档    : {}/swagger-ui.html", baseUrl);
            log.info("  OpenAPI     : {}/v3/api-docs", baseUrl);
            log.info("  前端页面    : http://localhost:5173/auth/login (需启动 frontend)");
            log.info("==============================================================");
        };
    }
}

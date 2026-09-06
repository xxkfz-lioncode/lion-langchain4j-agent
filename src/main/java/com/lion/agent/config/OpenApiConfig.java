package com.lion.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 在线接口文档配置(Swagger UI)
 * <p>
 * 访问入口(默认端口 8080): <a href="http://localhost:8080/swagger-ui.html">/swagger-ui.html</a>
 * OpenAPI 描述文件: <a href="http://localhost:8080/v3/api-docs">/v3/api-docs</a>
 * <p>
 * 在线调用步骤:
 * <ol>
 *   <li>调用「认证服务 - 登录」获取 token(默认账号 admin / 123456);</li>
 *   <li>点击 Swagger UI 右上角 Authorize, 将 token 填入 Value 后确认;</li>
 *   <li>之后即可在线调试所有需登录的接口; controller.test 包下接口免登录可直接调用。</li>
 * </ol>
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "satoken";

    @Bean
    public OpenAPI lionAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Lion LangChain4j Agent API")
                        .description("AI 智能助手后端接口文档; 登录后可将 token 填入 Authorize 以在线调试")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("satoken")
                                .description("登录接口返回的 token 值")))
                // 全局要求携带 satoken(登录/注册接口除外, 后端不会强制校验)
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}

package com.lion.agent.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 客户端三件套装配: {@link McpTransport} → {@link McpClient} → {@link McpToolProvider}。
 * <p>
 * <b>传输方式</b>: 同一时间只会装配其中一种 transport(由 {@code lion.mcp.transport} 决定):
 * <ul>
 *   <li>{@code http}(默认) —— {@link StreamableHttpMcpTransport} 通过 HTTP POST 与远程 MCP
 *       服务器通信, 适用 2025-06-18 协议, 适合独立部署 / SaaS;</li>
 *   <li>{@code stdio} —— {@link StdioMcpTransport} 由客户端 fork 启动本地 MCP Server 子进程,
 *       通过 stdin/stdout 与之通信, 适合本机工具(读文件、跑命令等)。</li>
 * </ul>
 * <pre>
 *   http 模式:                                            stdio 模式:
 *   ┌─────────────────────────┐  HTTP POST (JSON-RPC)    ┌─────────────────────────┐
 *   │  StreamableHttpTransport│ ──────────────────────── │  Remote MCP Server      │
 *   └──────────┬──────────────┘                          └─────────────────────────┘
 *              │
 *              │                            或
 *              ▼
 *   ┌─────────────────────────┐                         ┌─────────────────────────┐
 *   │  DefaultMcpClient       │                         │  StdioMcpTransport      │
 *   │  (握手 / 缓存 / 重连)   │                         │  (fork 子进程 + 管道)   │
 *   └──────────┬──────────────┘                         └──────────┬──────────────┘
 *              │                                                │
 *              └────────────────┬───────────────────────────────┘
 *                               ▼
 *                     ┌─────────────────────────┐
 *                     │  McpToolProvider        │  给 AiServices.toolProvider(...) 用
 *                     └─────────────────────────┘
 * </pre>
 * <p>
 * <b>关闭行为</b>: 所有 Bean 都带 {@code destroyMethod="close"}, Spring 容器关闭时会自动
 * 关闭 transport(HTTP 关连接池 / stdio 杀子进程)以及 MCP 客户端, 释放资源。
 * <p>
 * <b>总闸</b>: {@code lion.mcp.enabled=false} 时整个装配被 {@link ConditionalOnProperty} 短路;
 * 具体哪种 transport 被装配还受以下二级开关控制(互斥):
 * <ul>
 *   <li>http 模式: 需要 {@code lion.mcp.url} 非空;</li>
 *   <li>stdio 模式: 需要 {@code lion.mcp.transport=stdio} 且 {@code lion.mcp.command} 非空。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(McpProperties.class)
// 一级总闸: enabled=false 直接短路整个 MCP 装配; 具体的二级总闸(url / command)
// 下放到对应 @Bean 上, 让 http / stdio 两种模式互斥装配
@ConditionalOnProperty(prefix = "lion.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientConfig {

    /**
     * HTTP 传输。
     * <p>
     * 仅当 {@code lion.mcp.transport=http}(默认)且 {@code lion.mcp.url} 非空时装配。
     * {@code logRequests / logResponses} 把 HTTP 上的 JSON-RPC 流量打到 slf4j
     * (本类 logger), 排查协议握手问题非常有用; 生产环境建议改为 false。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "lion.mcp", name = "transport", havingValue = "http", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "lion.mcp", name = "url")
    public McpTransport mcpTransport(McpProperties props) {
        log.info("[MCP/http] 连接远端 MCP 服务器: {}", props.getUrl());

        StreamableHttpMcpTransport.Builder builder = StreamableHttpMcpTransport.builder()
                .url(props.getUrl())
                .logRequests(true)
                .logResponses(true);

        if (props.getHeaders() != null && !props.getHeaders().isEmpty()) {
            log.info("[MCP/http] 透传自定义 HTTP 头: {}", props.getHeaders().keySet());
            builder.customHeaders(props.getHeaders());
        }
        return builder.build();
    }

    /**
     * stdio 传输: 由客户端 fork 一个本地子进程跑 MCP Server, 通过 stdin/stdout 通信。
     * <p>
     * 仅当 {@code lion.mcp.transport=stdio} 时装配, 与 {@link #mcpTransport(McpProperties)}
     * HTTP Bean 互斥(同类型 Bean, 不会同时存在)。
     * <p>
     * <b>为什么不用 {@code @ConditionalOnProperty(name="command")}</b>: Spring Boot
     * 的 {@code OnPropertyCondition} 通过 {@code Environment.getProperty(...)} 判断
     * List 类型的属性时, 在某些版本/解析器下会拿到空字符串, 导致 Bean 永不创建。
     * 把校验挪到 Bean 方法里, 既可靠又能给出清晰的启动失败提示。
     * <p>
     * 进程生命周期由 transport 托管, Spring 容器关闭时 {@code destroyMethod="close"} 会
     * 杀掉子进程, 释放管道文件描述符。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "lion.mcp", name = "transport", havingValue = "stdio")
    public McpTransport mcpStdioTransport(McpProperties props) {
        List<String> cmd = props.getCommand();
        if (cmd == null || cmd.isEmpty()) {
            throw new IllegalStateException(
                    "[MCP/stdio] lion.mcp.transport=stdio 但 lion.mcp.command 未配置, "
                            + "请在 application.yml 填写子进程启动命令, 例如:\n"
                            + "  lion.mcp.command:\n"
                            + "    - npx.cmd\n"
                            + "    - -y\n"
                            + "    - \"@modelcontextprotocol/server-filesystem\"\n"
                            + "    - \"E:/xxkfz-project/lion-langchain4j-agent\"");
        }
        log.info("[MCP/stdio] 启动本地 MCP Server 子进程: {}", cmd);

        return new StdioMcpTransport.Builder()
                .command(cmd)
                .logEvents(true)
                .build();
    }

    /**
     * MCP 客户端: 负责握手 / 工具列表缓存 / 重连等所有协议层细节。
     * <p>
     * 用 {@link ObjectProvider} 注入是为了兼容 http / stdio 两种 transport Bean
     * 都是 {@code McpTransport} 类型; 二者由 {@code @ConditionalOnProperty} 互斥装配,
     * 此处永远只有一个候选, {@code getIfUnique()} 直接拿到。
     * <p>
     * {@code .key(...)} 给客户端起个名字, 多 MCP 客户端并存时(本项目目前只有一份,
     * 但演示场景值得保留命名)用于在 {@link McpToolProvider} 里消歧; 工具名映射
     * (McpToolNameMapper) 会按 client.key 加前缀避免冲突。
     */
    @Bean(destroyMethod = "close")
    public McpClient mcpClient(ObjectProvider<McpTransport> transportProvider, McpProperties props) {
        McpTransport transport = transportProvider.getIfAvailable();
        if (transport == null) {
            throw new IllegalStateException("[MCP] 未找到 McpTransport Bean, "
                    + "请检查 lion.mcp.transport (http|stdio) 及对应 url / command 是否配置正确");
        }

        return DefaultMcpClient.builder()
                .key(props.getClientKey())
                .transport(transport)
                .build();
    }

    /**
     * 工具提供者: 把 MCP 客户端里的工具适配成 LangChain4j 的 {@code ToolProvider},
     * 通过 {@code AiServices.builder(...).toolProvider(toolProvider)} 一次性接入。
     * <p>
     * 配了 {@code filterToolNames} 时只暴露白名单里的工具; 没配则把服务器的全部工具
     * 暴露给 LLM。
     */
    @Bean
    public McpToolProvider mcpToolProvider(McpClient mcpClient, McpProperties props) {
        McpToolProvider.Builder builder = McpToolProvider.builder()
                .mcpClients(mcpClient)
                .failIfOneServerFails(true);

        if (props.getFilterToolNames() != null && !props.getFilterToolNames().isEmpty()) {
            builder.filterToolNames(props.getFilterToolNames());
            log.info("[MCP] 工具白名单生效: {}", props.getFilterToolNames());
        }
        return builder.build();
    }
}
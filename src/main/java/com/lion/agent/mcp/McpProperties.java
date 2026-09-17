package com.lion.agent.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端配置项(读取 {@code lion.mcp.*})。
 * <p>
 * 设计目的: 演示用 MCP 客户端(连接远程 HTTP MCP 服务器)的开关 / 接入地址 / 自定义头部 /
 * 工具过滤项集中在一处, 方便关停演示或换服务器。
 * <p>
 * 字段说明:
 * <ul>
 *   <li>{@link #enabled} —— 总闸, false 时 {@link McpClientConfig} 不装配任何 Bean,
 *       Spring 容器里 {@code McpTransport} / {@code McpClient} / {@code McpToolProvider}
 *       都拿不到, 依赖它们的 {@link com.lion.agent.assistant.ManualAssistant} 也会因
 *       缺失注入而启动失败(预期行为);</li>
 *   <li>{@link #transport} —— 传输方式, {@code http}(默认)走 Streamable HTTP 协议;
 *       {@code stdio} 走本地子进程 stdin/stdout。两种方式互斥, 由 {@link McpClientConfig}
 *       里的 {@code @ConditionalOnProperty} 决定具体装配哪个 {@code McpTransport} Bean;</li>
 *   <li>{@link #url} —— HTTP 模式专用: MCP 服务器入口 URL, 例如 {@code http://localhost:8080/mcp}。
 *       transport=http 时必填(为空会短路 MCP 装配);</li>
 *   <li>{@link #command} —— stdio 模式专用: 启动本地 MCP Server 子进程的完整命令(包含可执行程序
 *       + 参数, 例如 {@code ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"]});
 *       transport=stdio 时必填, 为空则启动时直接抛错;</li>
 *   <li>{@link #headers} —— HTTP 模式专用: 透传给 MCP 服务器的 HTTP 头(例如鉴权 {@code Authorization}),
 *       每次请求都会带上; stdio 模式忽略;</li>
 *   <li>{@link #clientKey} —— 多 MCP 客户端并存时用于消歧的标识, 单实例场景无影响;</li>
 *   <li>{@link #filterToolNames} —— 白名单, 传给 {@code McpToolProvider.filterToolNames(...)},
 *       不写则把服务器列出的工具全部暴露给 LLM。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "lion.mcp")
public class McpProperties {

    /** 总开关; 默认开启, 关闭后 McpClientConfig 不装配任何 MCP 相关 Bean */
    private boolean enabled = true;

    /**
     * 传输方式:
     * <ul>
     *   <li>{@code http} —— Streamable HTTP(2025-06-18 协议主推), 客户端通过 HTTP POST
     *       与远程 MCP 服务器通信, 适合独立部署的 MCP 服务;</li>
     *   <li>{@code stdio} —— 本地子进程模式, 客户端 fork 启动一个 MCP Server 进程并通过
     *       stdin/stdout 与之通信, 适合本机工具(读文件、跑命令等)。</li>
     * </ul>
     * 默认 {@code http}; 两种模式互斥, 由 {@link McpClientConfig} 通过
     * {@code @ConditionalOnProperty} 决定装配哪个 {@code McpTransport} Bean。
     */
    private String transport = "http";

    /**
     * HTTP 模式专用: MCP 服务器的 Streamable HTTP 入口 URL。
     * <p>
     * transport=http 时, 客户端会向该 URL 发 POST 请求(JSON-RPC over HTTP), 服务端按
     * Streamable HTTP 规范响应。留空时 McpClientConfig 不装配任何 MCP 客户端(等同于关闭),
     * 避免误连到本地不存在的服务导致启动卡 handshake。
     */
    private String url;

    /**
     * stdio 模式专用: 启动本地 MCP Server 子进程的完整命令(可执行程序 + 参数列表)。
     * <p>
     * 第一个元素是可执行程序, 后续是参数; 例如:
     * <pre>
     *   command:
     *     - npx
     *     - -y
     *     - "@modelcontextprotocol/server-filesystem"
     *     - "/tmp"
     * </pre>
     * transport=stdio 时必填, 为空会让启动直接抛错。
     */
    private List<String> command;

    /**
     * HTTP 模式专用: 透传给 MCP 服务器的 HTTP 头(例如 {@code Authorization: Bearer xxx})。
     * <p>
     * 每次请求都会带上, 用于接入鉴权; 与 {@code McpHeadersSupplier} 等价的 Map 写法。
     * stdio 模式下无 HTTP, 此字段忽略。
     */
    private Map<String, String> headers;

    /** McpClient.key(), 多客户端并存时用于消歧; 单实例取默认即可 */
    private String clientKey = "lion-mcp-demo";

    /**
     * 工具白名单: 传给 {@code McpToolProvider.filterToolNames(...)},
     * 仅这些名字的工具会暴露给 LLM; 留空(null 或空集合)表示不过滤, 全部暴露。
     */
    private List<String> filterToolNames;
}
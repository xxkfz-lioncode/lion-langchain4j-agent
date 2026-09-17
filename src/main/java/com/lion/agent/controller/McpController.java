package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONUtil;
import com.lion.agent.assistant.ManualAssistant;
import com.lion.agent.common.Result;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.mcp.McpProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.TokenStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP(Model Context Protocol)调试联调接口(需登录)。
 * <p>
 * 本 controller 从原 {@code controller.test} 包迁出, 升级为正式业务接口:
 * 路径由 {@code /test/mcp/**} 改为 {@code /api/mcp/**}, 自动纳入 Sa-Token 登录校验;
 * 鉴权走 {@link com.lion.agent.config.SaTokenConfigure} 对 {@code /api/**} 的统一拦截。
 * <p>
 * <b>接口清单</b>:
 * <ul>
 *   <li>{@code GET  /api/mcp/info}            —— 连接信息: 是否启用 / 服务端 URL / clientKey / instructions / 工具总数;</li>
 *   <li>{@code GET  /api/mcp/tools}           —— 列出 MCP 服务端暴露的全部工具(含 JSON Schema 参数);</li>
 *   <li>{@code GET  /api/mcp/tools/{name}}    —— 单个工具的完整参数 Schema(供前端"工具详情"面板渲染);</li>
 *   <li>{@code POST /api/mcp/chat}            —— 阻塞对话: 让 ManualAssistant 决定是否调用 MCP 工具, 返回完整答复;</li>
 *   <li>{@code GET  /api/mcp/chat/stream}     —— SSE 流式对话: 千问每生成一个 token 即推送, 用于观察
 *       "模型 → 工具 → 模型"的完整思考链;</li>
 * </ul>
 * <p>
 * <b>降级策略</b>: 当 {@code lion.mcp.enabled=false} 或 {@code lion.mcp.url} 留空时,
 * {@link com.lion.agent.mcp.McpClientConfig} 整体不装配, {@code McpClient} / {@code McpToolProvider}
 * 注入为 {@code null}; 本 controller 用 {@code required = false} 容忍这种空状态, 但凡调用到
 * MCP 工具的接口都会抛 500 + 明确错误信息, 提示用户先去 application.yml 打开 MCP。
 *
 * @see com.lion.agent.assistant.ManualAssistant
 * @see com.lion.agent.mcp.McpClientConfig
 */
@Tag(name = "MCP 调试", description = "Model Context Protocol 联调接口: 工具列表 / 工具详情 / 模型对话(需登录)")
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    /** SSE 流结束标记 */
    private static final String STREAM_DONE = "[DONE]";

    /**
     * MCP 客户端与工具提供者(均为可选 —— MCP 未启用时为 null)。
     * <p>
     * 用 {@code required = false} 是因为 MCP 链路整体受
     * {@code com.lion.agent.mcp.McpClientConfig} 上的 {@code @ConditionalOnProperty} 控制,
     * 关闭 MCP 时这两个 Bean 都不存在, 注入必须容错。
     */
    private final McpClient mcpClient;
    private final McpProperties mcpProperties;
    private final ManualAssistant manualAssistant;

    public McpController(@Autowired(required = false) McpClient mcpClient,
                          McpProperties mcpProperties,
                          ManualAssistant manualAssistant) {
        this.mcpClient = mcpClient;
        this.mcpProperties = mcpProperties;
        this.manualAssistant = manualAssistant;
    }

    // ====================== 连接信息 ======================

    /**
     * MCP 客户端握手元信息(无需到服务端调用, 直接读取本地缓存)。
     * <p>
     * 用于前端页面顶部 banner, 一眼看出 "是否连上了远端 MCP, 连的是哪个, 有多少工具"。
     * 字段说明:
     * <ul>
     *   <li>{@code enabled} —— 总开关状态;</li>
     *   <li>{@code url} —— 远端 MCP 入口 URL(仅展示用, 不可写);</li>
     *   <li>{@code clientKey} —— 多客户端并存时的消歧标识;</li>
     *   <li>{@code instructions} —— MCP 服务端握手时返回的指令文本(可能为空);</li>
     *   <li>{@code toolCount} —— 当前已缓存的工具数; -1 表示 MCP 未启用。</li>
     * </ul>
     */
    @Operation(summary = "MCP 连接信息",
            description = "返回 enabled/url/clientKey/instructions/toolCount, 用于页面顶部状态栏")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", mcpProperties.isEnabled());
        data.put("url", mcpProperties.getUrl());
        data.put("clientKey", mcpProperties.getClientKey());
        if (mcpClient != null) {
            data.put("instructions", mcpClient.instructions() == null ? "" : mcpClient.instructions());
            try {
                data.put("toolCount", mcpClient.listTools().size());
            } catch (Exception e) {
                // 握手失败时 listTools 抛错, 不阻塞基础信息展示, 工具数标 -1
                data.put("toolCount", -1);
                data.put("toolsError", e.getMessage());
            }
        } else {
            data.put("instructions", "");
            data.put("toolCount", -1);
        }
        return Result.ok(data);
    }

    // ====================== 工具列表 ======================

    /**
     * 列出 MCP 服务端暴露的全部工具: 含名称 / 描述 / 完整参数 JSON Schema。
     * <p>
     * 注意: 直接返回 {@link ToolSpecification} 会被 Jackson 拒(没有 JavaBean 风格的
     * {@code getXxx()} 方法); 这里借助 Hutool 把参数 Schema 还原成标准 JSON 后再入 Map,
     * 前端拿到就能直接 JSON.stringify 或渲染到 JSON Viewer。
     */
    @Operation(summary = "列出 MCP 工具",
            description = "返回每个工具的 name / description / parameters(JSON Schema); 不经模型")
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> listTools() {
        ensureMcpReady();
        List<Map<String, Object>> tools = mcpClient.listTools().stream()
                .map(McpController::describe)
                .toList();
        return Result.ok(tools);
    }

    /**
     * 单个工具的完整参数 Schema: 路径变量是工具名(URL 编码)。
     * <p>
     * 用于"工具详情"面板按需懒加载, 比一次性把全部工具的完整 Schema 推到前端更省带宽。
     */
    @Operation(summary = "单个工具的参数 Schema",
            description = "按工具名查询, 返回完整 JSON Schema(供前端 JSON Viewer 渲染)")
    @GetMapping("/tools/{name}")
    public Result<Map<String, Object>> toolDetail(
            @Parameter(description = "工具名(URL 编码)", required = true, example = "echo")
            @PathVariable("name") String name) {
        ensureMcpReady();
        return mcpClient.listTools().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .map(McpController::describe)
                .map(Result::ok)
                .orElseThrow(() -> new BusinessException("工具不存在: " + name));
    }

    // ====================== 对话(阻塞) ======================

    /**
     * 让 ManualAssistant 处理用户消息: 模型可同时调用编程式静态工具 / Skills / MCP 工具,
     * 由 LLM 自行决定何时触发哪个。
     * <p>
     * 请求体: {@code { "message": "...", "memoryId": "可选, 不传则按登录用户隔离" }}
     * <p>
     * 与 {@code /api/chat/send} 的区别: 本接口走 {@link ManualAssistant}, 启用了 MCP 链路;
     * {@code /api/chat/send} 走业务对话服务, 不带 MCP 工具。
     */
    @Operation(summary = "阻塞对话(MCP 链路)",
            description = "交给 ManualAssistant 处理; 模型可调用编程式工具 / Skills / MCP 工具; "
                    + "memoryId 留空时按登录用户隔离上下文")
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody ChatBody body) {
        String message = body == null ? null : body.message;
        if (!StringUtils.hasText(message)) {
            throw new BusinessException("消息内容不能为空");
        }
        String memoryId = (body != null && StringUtils.hasText(body.memoryId))
                ? body.memoryId
                : defaultMemoryId();

        String reply = manualAssistant.chat(memoryId, message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memoryId", memoryId);
        data.put("reply", reply);
        return Result.ok(data);
    }

    /**
     * SSE 流式对话: 观察模型逐步生成 + 是否触发 MCP 工具调用(可从 token 间停顿看出工具调用窗口)。
     * <p>
     * 事件格式与 {@code /api/chat/stream} 一致: 逐 token 推送, 结束发 {@code data: [DONE]}。
     */
    @Operation(summary = "流式对话(MCP 链路)",
            description = "text/event-stream, 每行 data: 推送一个 token, 结束推送 data: [DONE]")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @Parameter(description = "用户消息(URL 编码)", required = true)
            @org.springframework.web.bind.annotation.RequestParam("message") String message,
            @Parameter(description = "memoryId, 留空按登录用户隔离", example = "mcp-user-1")
            @org.springframework.web.bind.annotation.RequestParam(value = "memoryId", required = false) String memoryId) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException("消息内容不能为空");
        }
        String mid = StringUtils.hasText(memoryId) ? memoryId : defaultMemoryId();

        SseEmitter emitter = new SseEmitter(0L);
        try {
            TokenStream tokenStream = manualAssistant.chatStream(mid, message);
            tokenStream
                    .onPartialResponse(partial -> send(emitter, partial))
                    .onCompleteResponse(response -> {
                        send(emitter, STREAM_DONE);
                        emitter.complete();
                    })
                    .onError(emitter::completeWithError)
                    .start();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ====================== 内部辅助 ======================

    /**
     * MCP 未装配时, 任何用到 McpClient 的接口都必须给出明确错误,
     * 避免返回 500 + NPE 让前端困惑。
     */
    private void ensureMcpReady() {
        if (mcpClient == null) {
            throw new BusinessException(
                    "MCP 未启用: 请在 application.yml 设置 lion.mcp.url 指向远端 MCP, "
                            + "并保持 lion.mcp.enabled=true");
        }
    }

    /**
     * 默认 memoryId: 按登录用户隔离(类似 ChatController 的做法),
     * 保证同一用户的多次调用共享上下文。
     */
    private String defaultMemoryId() {
        return "mcp:" + StpUtil.getLoginIdAsString();
    }

    /** SSE 写一个 data 事件; 写失败则结束整个流 */
    private void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * ToolSpecification → 扁平化 Map(给前端展示)。
     * <p>
     * 关键点: {@code spec.parameters()} 是 {@code JsonObjectSchema}, Jackson 默认按 bean 规则
     * 找不到属性而抛 "no properties discovered to create BeanSerializer"; 借助 Hutool 把
     * {@link ToolSpecification#toJson()} 标准 JSON 字符串解析成 {@code JSONObject}(本质是
     * LinkedHashMap), 完整保留 JSON Schema 结构(type / properties / required / items / enum / ...)。
     * <p>
     * 失败兜底走 {@code parameters().toString()} —— 至少给前端一串可读文本。
     */
    static Map<String, Object> describe(ToolSpecification spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", spec.name());
        m.put("description", spec.description());
        try {
            m.put("parameters", JSONUtil.parseObj(spec.toJson()).get("parameters"));
        } catch (JSONException e) {
            m.put("parameters", spec.parameters().toString());
        }
        return m;
    }

    /** 对话请求体(message 必填, memoryId 选填) */
    @Setter
    @Getter
    public static class ChatBody {
        private String message;
        private String memoryId;
    }

    /**
     * 简单的 URI 校验占位(目前未直接使用, 保留给后续"自定义 URL 测试"接口用)。
     * 注释保留: 校验 URI 字符串是否合法可解析为 http/https, 避免拼错 URL 导致握手挂死。
     */
    @SuppressWarnings("unused")
    private static boolean isHttpUri(String s) {
        if (!StringUtils.hasText(s)) return false;
        try {
            URI u = URI.create(s);
            String scheme = u.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }
}
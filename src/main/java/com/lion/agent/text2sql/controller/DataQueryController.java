package com.lion.agent.text2sql.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.lion.agent.text2sql.pipeline.StepMeta;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.R;
import com.lion.agent.text2sql.pojo.dto.FeedbackRequest;
import com.lion.agent.text2sql.pojo.dto.Text2SqlQuery;
import com.lion.agent.text2sql.pojo.vo.BadCaseVO;
import com.lion.agent.text2sql.pojo.vo.SchemaColumnVO;
import com.lion.agent.text2sql.pojo.vo.SchemaTableVO;
import com.lion.agent.text2sql.pojo.vo.Text2SqlResult;
import com.lion.agent.text2sql.service.DataQueryService;
import com.lion.agent.text2sql.service.FeedbackService;
import com.lion.agent.text2sql.service.SchemaManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 智能问数接口(需登录)。
 * <p>
 * 主入口是 {@link #stream(String, Long)} 的 SSE 流式问数: 单条 {@code data:} 流,
 * 每条为带 {@code type} 字段的 JSON(progress / sql / result / error), 结束推 {@code [DONE]}。
 * 前端可直接复用 {@code api/chat.js} 的 SSE 解析方式。
 */
@Tag(name = "智能问数", description = "自然语言查数据库(只读, 需登录)")
@RestController
@RequestMapping("/api/text2sql")
public class DataQueryController {

    private static final String STREAM_DONE = "[DONE]";

    private final DataQueryService dataQueryService;
    private final SchemaManager schemaManager;
    private final FeedbackService feedbackService;

    public DataQueryController(DataQueryService dataQueryService,
                              SchemaManager schemaManager,
                              FeedbackService feedbackService) {
        this.dataQueryService = dataQueryService;
        this.schemaManager = schemaManager;
        this.feedbackService = feedbackService;
    }

    // ==================== 问数 ====================

    @Operation(summary = "问数流程步骤",
            description = "返回流程编排的全部步骤清单, 供前端在提问前预渲染步骤条(与实际执行同一数据源)")
    @GetMapping("/pipeline/steps")
    public R<List<StepMeta>> pipelineSteps() {
        return R.ok(dataQueryService.describePipeline());
    }

    /**
     * 流式问数(SSE)。
     *
     * @param question       自然语言问题(URL 编码)
     * @param conversationId 会话id(可选, 用于多轮追问)
     */
    @Operation(summary = "流式问数(SSE)",
            description = "text/event-stream; 事件类型 progress/sql/result/error, 结束推 data: [DONE]")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Parameter(description = "自然语言问题(URL 编码)", required = true, example = "上个月各城市的订单金额")
            @RequestParam("question") String question,
            @Parameter(description = "会话id, 可选", example = "1")
            @RequestParam(value = "conversationId", required = false) Long conversationId) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }
        Long userId = currentUserId();
        Text2SqlQuery query = new Text2SqlQuery();
        query.setQuestion(question.trim());
        query.setConversationId(conversationId);

        SseEmitter emitter = new SseEmitter(0L);
        // 异步执行, 避免阻塞 Tomcat 线程
        new Thread(() -> {
            try {
                dataQueryService.queryStream(query, userId, new SseText2SqlSink(emitter));
            } catch (Exception e) {
                sendJson(emitter, "{\"type\":\"error\",\"errorType\":\"ERROR\",\"message\":"
                        + quote(e.getMessage()) + "}");
                send(emitter, STREAM_DONE);
                emitter.complete();
            }
        }, "text2sql-stream-" + System.currentTimeMillis()).start();
        return emitter;
    }

    /**
     * 同步问数(兜底 / 导出)。
     */
    @Operation(summary = "同步问数", description = "阻塞式返回完整结果; 失败/澄清也会以结果体返回")
    @PostMapping("/query")
    public R<Text2SqlResult> query(@RequestBody @Valid Text2SqlQuery query) {
        Long userId = currentUserId();
        Text2SqlResult result = dataQueryService.query(query, userId);
        return R.ok(result);
    }

    // ==================== 元数据导航 ====================

    @Operation(summary = "表列表", description = "仅返回允许问数查询的表; keyword 为空时返回全部")
    @GetMapping("/schema/tables")
    public R<List<SchemaTableVO>> listTables(
            @Parameter(description = "关键字(表名/注释模糊匹配)", example = "订单")
            @RequestParam(value = "keyword", required = false) String keyword) {
        return R.ok(schemaManager.listTables(keyword));
    }

    @Operation(summary = "字段列表", description = "指定表的字段元数据")
    @GetMapping("/schema/columns")
    public R<List<SchemaColumnVO>> listColumns(
            @Parameter(description = "表名", required = true, example = "biz_order")
            @RequestParam("tableName") String tableName) {
        return R.ok(schemaManager.listColumns(tableName));
    }

    @Operation(summary = "JOIN 路径", description = "根据外键推断两表之间的关联路径")
    @GetMapping("/schema/join-path")
    public R<List<SchemaManager.JoinPath>> joinPath(
            @RequestParam("from") String from,
            @RequestParam("to") String to) {
        return R.ok(schemaManager.inferJoinPaths(from, to));
    }

    @Operation(summary = "刷新元数据", description = "重新扫描目标库并覆盖语义层; 需管理员")
    @PostMapping("/schema/refresh")
    public R<String> refresh() {
        String version = schemaManager.fullRefresh();
        return R.ok(version);
    }

    // ==================== 反馈 ====================

    @Operation(summary = "提交反馈", description = "对某次问数结果点赞/点踩, 踩时可附纠错 SQL")
    @PostMapping("/feedback")
    public R<Void> feedback(@RequestBody @Valid FeedbackRequest request) {
        feedbackService.submit(request, currentUserId());
        return R.ok();
    }

    @Operation(summary = "bad case 列表", description = "待处理的负反馈(运营后台)")
    @GetMapping("/feedback/bad-cases")
    public R<PageResult<BadCaseVO>> badCases(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(feedbackService.listBadCases(page, size));
    }

    @Operation(summary = "推进反馈处理状态",
            description = "status=HANDLED(已处理, 可沉淀示例) 或 IGNORED(已忽略)")
    @PostMapping("/feedback/handle")
    public R<Void> handleFeedback(
            @RequestParam("feedbackId") Long feedbackId,
            @Parameter(description = "目标状态: HANDLED/IGNORED")
            @RequestParam(value = "status", defaultValue = "HANDLED") String status,
            @RequestParam(value = "promoteToFewshot", defaultValue = "false") boolean promoteToFewshot) {
        feedbackService.updateStatus(feedbackId, status, promoteToFewshot);
        return R.ok();
    }

    // ==================== 内部: SSE 事件回调 ====================

    private static class SseText2SqlSink implements DataQueryService.Text2SqlEventSink {

        private final SseEmitter emitter;

        SseText2SqlSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onProgress(String message) {
            sendJson(emitter, "{\"type\":\"progress\",\"message\":" + quote(message) + "}");
        }

        @Override
        public void onPlan(List<StepMeta> steps) {
            StringBuilder sb = new StringBuilder("{\"type\":\"plan\",\"steps\":[");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":").append(quote(steps.get(i).id()))
                        .append(",\"title\":").append(quote(steps.get(i).title())).append('}');
            }
            sb.append("]}");
            sendJson(emitter, sb.toString());
        }

        @Override
        public void onStep(String id, String title, String status, long costMs, String message) {
            sendJson(emitter, "{\"type\":\"step\",\"id\":" + quote(id)
                    + ",\"title\":" + quote(title)
                    + ",\"status\":\"" + status + "\""
                    + ",\"ms\":" + costMs
                    + ",\"message\":" + quote(message == null ? "" : message) + "}");
        }

        @Override
        public void onSql(String sql, String explanation) {
            sendJson(emitter, "{\"type\":\"sql\",\"sql\":" + quote(sql)
                    + ",\"explanation\":" + quote(explanation) + "}");
        }

        @Override
        public void onResult(Text2SqlResult result) {
            try {
                // Hutool 序列化, 日期按 dateFormat 输出为可读字符串。
                // 之前这里 new ObjectMapper() 未注册 JSR310 模块, 结果行含 DATE(created_at)
                // (java.time.LocalDate) 时直接抛 "not supported by default"
                String json = JSONUtil.toJsonStr(result,
                        JSONConfig.create().setDateFormat("yyyy-MM-dd HH:mm:ss"));
                sendJson(emitter, "{\"type\":\"result\"," + json.substring(1));
            } catch (Exception e) {
                sendJson(emitter, "{\"type\":\"error\",\"errorType\":\"ERROR\",\"message\":"
                        + quote("结果序列化失败: " + e.getMessage()) + "}");
            }
            send(emitter, STREAM_DONE);
            emitter.complete();
        }

        @Override
        public void onError(String errorType, String message) {
            sendJson(emitter, "{\"type\":\"error\",\"errorType\":" + quote(errorType)
                    + ",\"message\":" + quote(message) + "}");
            send(emitter, STREAM_DONE);
            emitter.complete();
        }
    }

    private static void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static void sendJson(SseEmitter emitter, String json) {
        send(emitter, json);
    }

    /** 极简 JSON 字符串转义(仅用于拼接进度/错误文案) */
    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Long currentUserId() {
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }
}

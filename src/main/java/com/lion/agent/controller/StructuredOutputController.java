package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.Result;
import com.lion.agent.structured.StructuredOutputService;
import com.lion.agent.structured.StructuredOutputStrategy;
import com.lion.agent.structured.model.MovieReview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出(需登录): 三种方式 + 自动降级 + 三方式对照的业务接口。
 * <p>
 * 设计目标: 让前端「结构化输出测试」页面可以一键调用三种方式 + 自动降级 + 三方式对照,
 * 不必关心底层 langchain4j 的 ChatRequest / JsonSchema 等。
 *
 * @see StructuredOutputService
 */
@Tag(name = "结构化输出", description = "LangChain4j 结构化输出三种方式的业务接口(需登录)")
@Slf4j
@RestController
@RequestMapping("/api/structured")
@RequiredArgsConstructor
public class StructuredOutputController {

    private final StructuredOutputService structuredOutputService;

    // ====================== 元信息 ======================

    @Operation(summary = "已装配的方式", description = "页面 banner / 选项卡用, 返回每种方式的 key / 中文名 / 描述")
    @GetMapping("/strategies")
    public Result<List<Map<String, String>>> strategies() {
        List<Map<String, String>> list = Arrays.stream(StructuredOutputStrategy.values())
                .map(s -> Map.of(
                        "key", s.name(),
                        "label", s.label(),
                        "description", s.description()))
                .toList();
        return Result.ok(list);
    }

    // ====================== 单方式抽取 ======================

    @Operation(summary = "按指定方式抽取",
            description = "strategy 取 JSON_SCHEMA / JSON_MODE / PROMPT_ONLY 之一; 返回解析后的 MovieReview")
    @PostMapping("/extract")
    public Result<MovieReview> extract(@RequestBody ExtractRequest body) {
        if (body == null || !StringUtils.hasText(body.text)) {
            return Result.fail(400, "text 不能为空");
        }
        StructuredOutputStrategy strategy;
        try {
            strategy = StructuredOutputStrategy.valueOf(body.strategy);
        } catch (Exception e) {
            return Result.fail(400, "strategy 取值必须是 JSON_SCHEMA / JSON_MODE / PROMPT_ONLY 之一");
        }
        logCurrentUser();
        MovieReview value = structuredOutputService.extract(body.text.trim(), MovieReview.target(), strategy);
        return Result.ok(value);
    }

    // ====================== 自动降级 ======================

    @Operation(summary = "自动降级抽取",
            description = "按 JSON Schema -> JSON Mode -> 纯提示词 顺序尝试, 返回第一个成功的结果 + 沿途失败原因")
    @PostMapping("/auto")
    public Result<Map<String, Object>> auto(@RequestBody TextRequest body) {
        if (body == null || !StringUtils.hasText(body.text)) {
            return Result.fail(400, "text 不能为空");
        }
        logCurrentUser();
        StructuredOutputService.FallbackResult<MovieReview> r =
                structuredOutputService.extractWithFallback(body.text.trim(), MovieReview.target());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", body.text);
        resp.put("usedStrategy", r.usedStrategy().label());
        resp.put("value", r.value());
        resp.put("failures", r.failures());
        return Result.ok(resp);
    }

    // ====================== 三种方式对照 ======================

    @Operation(summary = "三种方式对照抽取",
            description = "同一条文本上跑三种方式, 返回每种方式的 success / value 或 error / costMillis")
    @PostMapping("/compare")
    public Result<Map<String, Object>> compare(@RequestBody TextRequest body) {
        if (body == null || !StringUtils.hasText(body.text)) {
            return Result.fail(400, "text 不能为空");
        }
        logCurrentUser();
        String text = body.text.trim();
        Map<String, Object> results = new LinkedHashMap<>();
        for (StructuredOutputStrategy s : StructuredOutputStrategy.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            long t = System.currentTimeMillis();
            try {
                Object v = structuredOutputService.extract(text, MovieReview.target(), s);
                item.put("success", true);
                item.put("value", v);
            } catch (Exception e) {
                item.put("success", false);
                item.put("error", e.getMessage());
            }
            item.put("costMillis", System.currentTimeMillis() - t);
            results.put(s.label(), item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("results", results);
        return Result.ok(resp);
    }

    /** 当前用户日志(实际鉴权由 Sa-Token 拦截器统一处理, 这里只记录便于排查) */
    private void logCurrentUser() {
        try {
            long uid = Long.parseLong(StpUtil.getLoginIdAsString());
            log.debug("[结构化输出] 当前用户 id={}", uid);
        } catch (Exception ignored) {
            // 未登录等异常交给 Sa-Token 拦截器, 这里不阻断
        }
    }

    // ====================== 入参 ======================

    /** 单方式抽取入参: text + strategy(字符串, 与前端 JSON 友好对接) */
    public static class ExtractRequest {
        public String text;
        public String strategy;
    }

    /** 通用文本入参 */
    public static class TextRequest {
        public String text;
    }
}
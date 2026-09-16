package com.lion.agent.controller.test;

import com.lion.agent.structured.StructuredOutputService;
import com.lion.agent.structured.StructuredOutputStrategy;
import com.lion.agent.structured.StructuredTarget;
import com.lion.agent.structured.model.MovieReview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化输出三种方式 + 自动降级 + 三方式对照的联调接口(免登录)。
 * <p>
 * 典型用法:
 * <ul>
 *   <li>{@code /test/structured/json-schema?text=...} —— 方式一, 看模型按 schema 输出的精确度;</li>
 *   <li>{@code /test/structured/json-mode?text=...} —— 方式二, 千问(DashScope)主力方式;</li>
 *   <li>{@code /test/structured/prompt?text=...} —— 方式三, 看模型纯按提示词的"野生输出";</li>
 *   <li>{@code /test/structured/auto?text=...} —— 不知道模型是否支持 json_schema 时首选;</li>
 *   <li>{@code /test/structured/compare?text=...} —— 同一条文本上跑三种方式, 一键对照结果与耗时。</li>
 * </ul>
 */
@Tag(name = "测试-结构化输出",
        description = "LangChain4j 结构化输出三种方式(JSON Schema / 提示词+JSON Mode / 纯提示词)的联调接口, 免登录")
@Slf4j
@RestController
@RequestMapping("/test/structured")
@RequiredArgsConstructor
public class StructuredOutputTestController {

    /** Swagger 「Try it out」直接点执行时用的默认样例影评, 与 demo 字段类型对应 */
    private static final String SAMPLE_REVIEW = "刚看完《星际穿越》, 诺兰的叙事依旧震撼, 配乐也很赞。给个 9 分吧, 强烈推荐, 影评人老王。";

    private final StructuredOutputService structuredOutputService;

    @Operation(summary = "方式一·JSON Schema",
            description = "请求里带 response_format=json_schema, 由模型侧约束输出结构(最可靠)。")
    @GetMapping("/json-schema")
    public Map<String, Object> jsonSchema(
            @Parameter(description = "待抽取的自由文本(不传则用内置影评样例)", example = SAMPLE_REVIEW)
            @RequestParam(value = "text", required = false, defaultValue = SAMPLE_REVIEW) String text) {
        MovieReview review = structuredOutputService.extract(text, MovieReview.target(), StructuredOutputStrategy.JSON_SCHEMA);
        return ok(StructuredOutputStrategy.JSON_SCHEMA, review);
    }

    @Operation(summary = "方式二·提示词 + JSON Mode",
            description = "提示词描述字段 + response_format=json_object, 千问(DashScope)主力方式。")
    @GetMapping("/json-mode")
    public Map<String, Object> jsonMode(
            @Parameter(description = "待抽取的自由文本(不传则用内置影评样例)", example = SAMPLE_REVIEW)
            @RequestParam(value = "text", required = false, defaultValue = SAMPLE_REVIEW) String text) {
        MovieReview review = structuredOutputService.extract(text, MovieReview.target(), StructuredOutputStrategy.JSON_MODE);
        return ok(StructuredOutputStrategy.JSON_MODE, review);
    }

    @Operation(summary = "方式三·仅提示词",
            description = "请求里不设 response_format, 结构约束全部写在提示词里(最不可靠)。")
    @GetMapping("/prompt")
    public Map<String, Object> promptOnly(
            @Parameter(description = "待抽取的自由文本(不传则用内置影评样例)", example = SAMPLE_REVIEW)
            @RequestParam(value = "text", required = false, defaultValue = SAMPLE_REVIEW) String text) {
        MovieReview review = structuredOutputService.extract(text, MovieReview.target(), StructuredOutputStrategy.PROMPT_ONLY);
        return ok(StructuredOutputStrategy.PROMPT_ONLY, review);
    }

    @Operation(summary = "自动降级",
            description = "按 JSON Schema -> JSON Mode -> 纯提示词 顺序尝试, 返回第一个成功的结果, 并附上失败原因。")
    @GetMapping("/auto")
    public Map<String, Object> auto(
            @Parameter(description = "待抽取的自由文本(不传则用内置影评样例)", example = SAMPLE_REVIEW)
            @RequestParam(value = "text", required = false, defaultValue = SAMPLE_REVIEW) String text) {
        StructuredOutputService.FallbackResult<MovieReview> result =
                structuredOutputService.extractWithFallback(text, MovieReview.target());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("usedStrategy", result.usedStrategy().label());
        resp.put("value", result.value());
        resp.put("failures", result.failures());
        return resp;
    }

    @Operation(summary = "三种方式对照",
            description = "同一条文本上跑三种方式, 返回每种方式的输出/错误/耗时, 用于排查'哪种方式更适合当前模型'。")
    @GetMapping("/compare")
    public Map<String, Object> compare(
            @Parameter(description = "待抽取的自由文本(不传则用内置影评样例)", example = SAMPLE_REVIEW)
            @RequestParam(value = "text", required = false, defaultValue = SAMPLE_REVIEW) String text) {
        StructuredTarget<MovieReview> target = MovieReview.target();
        Map<String, Object> results = new LinkedHashMap<>();
        for (StructuredOutputStrategy strategy : StructuredOutputStrategy.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            long t = System.currentTimeMillis();
            try {
                Object value = structuredOutputService.extract(text, target, strategy);
                item.put("success", true);
                item.put("value", value);
            } catch (Exception e) {
                item.put("success", false);
                item.put("error", e.getMessage());
            }
            item.put("costMillis", System.currentTimeMillis() - t);
            results.put(strategy.label(), item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("results", results);
        return resp;
    }

    private static Map<String, Object> ok(StructuredOutputStrategy strategy, Object value) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("strategy", strategy.label());
        resp.put("value", value);
        return resp;
    }
}
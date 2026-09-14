package com.lion.agent.controller.test;

import com.lion.agent.classification.Sentiment;
import com.lion.agent.classification.SentimentAnalyzer;
import com.lion.agent.classification.SentimentEmbeddingClassifier;
import dev.langchain4j.classification.ClassificationResult;
import dev.langchain4j.classification.ScoredLabel;
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
import java.util.List;
import java.util.Map;

/**
 * 情感分类两种实现的联调接口(免登录): 测试 {@link SentimentAnalyzer}(LLM)与
 * {@link SentimentEmbeddingClassifier}(Embedding) 在同一条文本上的输出差异。
 * <p>
 * 与其他 test controller 一样位于 controller.test 包, Sa-Token 拦截器自动放行;
 * 所有端点都是 GET, 通过 {@code text} 参数传入待分类文本(支持中文, 浏览器/curl 直接调用)。
 * <p>
 * 典型用法:
 * <ul>
 *   <li>调 LLM 端点看模型直接判定的标签;</li>
 *   <li>调 Embedding 端点看相似度匹配结果;</li>
 *   <li>调 {@code /compare} 一键跑两种, 对照输出排查"两边结果不一致"的根因。</li>
 * </ul>
 */
@Tag(name = "测试-文本分类", description = "情感分类两种实现(LLM / Embedding)的联调接口, 免登录")
@Slf4j
@RestController
@RequestMapping("/test/classify")
@RequiredArgsConstructor
public class ClassificationTestController {

    private final SentimentAnalyzer sentimentAnalyzer;
    private final SentimentEmbeddingClassifier embeddingClassifier;

    /** 方式一: LLM 三分类, 返回 Sentiment 枚举 + 中文 raw + 映射后的枚举(便于对照稳定性) */
    @Operation(summary = "方式一·LLM 三分类(POSITIVE/NEUTRAL/NEGATIVE)",
            description = "同时返回 3 个结果: enum(框架结构化映射), raw(模型原始输出), mapped(自己再映射一次), "
                    + "便于观察模型是否偶发输出非枚举值。")
    @GetMapping("/llm")
    public Map<String, Object> classifyByLlm(
            @Parameter(description = "待分类文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text) {
        String raw = sentimentAnalyzer.classifyRaw(text);
        Sentiment mapped = sentimentAnalyzer.mapRawToSentiment(raw);
        Sentiment direct;
        try {
            direct = sentimentAnalyzer.classify(text);
        } catch (Exception e) {
            // 模型偶发输出非枚举值时(如小写 "positive"), 直接枚举分类会抛 IAE, 此处兜底
            log.warn("[分类测试-LLM] enum 分类失败, 改用 raw + mapped: {}", e.toString());
            direct = mapped;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("enum", direct);
        resp.put("raw", raw);
        resp.put("mapped", mapped);
        return resp;
    }

    /** 方式一: LLM 二分类, 是否正面情绪 */
    @Operation(summary = "方式一·LLM 二分类(是否正面)",
            description = "演示 boolean 返回类型 — 框架同样会约束模型只输出 true/false。")
    @GetMapping("/llm/positive")
    public Map<String, Object> isPositiveByLlm(
            @Parameter(description = "待分类文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("positive", sentimentAnalyzer.isPositive(text));
        return resp;
    }

    /** 方式二: Embedding 分类, 返回相似度最高的标签列表(默认 maxResults=1) */
    @Operation(summary = "方式二·Embedding 分类(仅标签)",
            description = "底层走 yml 配置的 openAiEmbeddingModel(DashScope text-embedding-v3), "
                    + "与每个标签的示例句算相似度, 按 maxResults 截断后返回标签列表。")
    @GetMapping("/embedding")
    public Map<String, Object> classifyByEmbedding(
            @Parameter(description = "待分类文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text) {
        List<Sentiment> tags = embeddingClassifier.classify(text);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("tags", tags);
        return resp;
    }

    /** 方式二: Embedding 分类带分数, 用于排查"为什么判错" */
    @Operation(summary = "方式二·Embedding 分类(带相似度分数)",
            description = "返回每个标签及其相似度分数(0~1), 排查 Embedding 判错时的首选端点。")
    @GetMapping("/embedding/scores")
    public Map<String, Object> classifyByEmbeddingWithScores(
            @Parameter(description = "待分类文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text) {
        ClassificationResult<Sentiment> result = embeddingClassifier.classifyWithScores(text);
        // 把 ScoredLabel 列表摊平成 Map<label, score>, 便于前端直接展示
        Map<Sentiment, Double> scores = new LinkedHashMap<>();
        for (ScoredLabel<Sentiment> sl : result.scoredLabels()) {
            scores.put(sl.label(), sl.score());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("scores", scores);
        return resp;
    }

    /** 一键跑两种, 同一文本上对照输出(排查两边结果不一致的最快路径) */
    @Operation(summary = "两种方式对照(同一条文本)",
            description = "并行跑两种分类器, 把结果并排返回; 当两种方式输出冲突时, 优先看 embedding/scores 的分数分布。")
    @GetMapping("/compare")
    public Map<String, Object> compare(
            @Parameter(description = "待分类文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text) {
        // LLM 侧
        String raw = sentimentAnalyzer.classifyRaw(text);
        Sentiment mapped = sentimentAnalyzer.mapRawToSentiment(raw);
        Sentiment direct;
        try {
            direct = sentimentAnalyzer.classify(text);
        } catch (Exception e) {
            direct = mapped;
        }

        // Embedding 侧
        List<Sentiment> tags = embeddingClassifier.classify(text);
        Map<Sentiment, Double> scoreMap = new LinkedHashMap<>();
        for (ScoredLabel<Sentiment> sl : embeddingClassifier.classifyWithScores(text).scoredLabels()) {
            scoreMap.put(sl.label(), sl.score());
        }

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("enum", direct);
        llm.put("raw", raw);
        llm.put("mapped", mapped);

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("tags", tags);
        embedding.put("scores", scoreMap);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("llm", llm);
        resp.put("embedding", embedding);
        resp.put("consistent", direct == (tags.isEmpty() ? null : tags.get(0)));
        return resp;
    }
}
package com.lion.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.classification.Sentiment;
import com.lion.agent.classification.SentimentAnalyzer;
import com.lion.agent.classification.SentimentEmbeddingClassifier;
import com.lion.agent.common.Result;
import dev.langchain4j.classification.ClassificationResult;
import dev.langchain4j.classification.ScoredLabel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 情感分析业务接口(需登录): 围绕 {@link Sentiment} 枚举把分类能力落地到三类使用场景。
 * <p>
 * 设计目标: 让前端情感分析页面可以一键调用, 而不必关心底层是 LLM 还是 Embedding 分类器。
 * 三个场景对应三个端点, 语义贴近业务:
 * <ul>
 *   <li>{@link #analyzeFeedback(FeedbackRequest)} — 客户反馈分析: 多条评论 → 情感分布 + 高频褒贬词;</li>
 *   <li>{@link #analyzeSocial(SocialRequest)} — 社交媒体监控: 单条社媒文本 + 平台 → 情感 + 严重度 + 命中关键词;</li>
 *   <li>{@link #replyChat(ChatReplyRequest)} — 聊天机器人响应: 对话历史 + 当前用户消息 → 情感 + 建议语气 + 模板回复。</li>
 * </ul>
 * 其余为通用单条 / 批量分类, 以及场景描述拉取。
 *
 * @see SentimentAnalyzer
 * @see SentimentEmbeddingClassifier
 */
@Tag(name = "情感分析", description = "情感分类的三个业务场景接口(需登录)")
@Slf4j
@RestController
@RequestMapping("/api/sentiment")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentAnalyzer sentimentAnalyzer;
    private final SentimentEmbeddingClassifier embeddingClassifier;

    // ====================== 通用分类 ======================

    @Operation(summary = "单条情感分类", description = "mode=llm 走 LLM, embedding 走向量相似度, both 返回两种结果")
    @GetMapping("/classify")
    public Result<Map<String, Object>> classify(
            @Parameter(description = "待分析文本", required = true, example = "东西收到了, 还行")
            @RequestParam("text") String text,
            @Parameter(description = "分类方式", example = "llm")
            @RequestParam(value = "mode", required = false, defaultValue = "llm") String mode) {
        validateText(text);
        return Result.ok(buildClassifyResult(text, mode));
    }

    @Operation(summary = "批量情感分类", description = "一次性分析多条文本, 上限 50 条; 返回每条的分类结果")
    @PostMapping("/batch")
    public Result<List<Map<String, Object>>> batch(@RequestBody BatchRequest body) {
        if (body == null || body.texts == null || body.texts.isEmpty()) {
            return Result.ok(List.of());
        }
        List<String> texts = body.texts;
        if (texts.size() > 50) {
            return Result.fail(400, "单次最多分析 50 条, 当前 " + texts.size() + " 条");
        }
        List<Map<String, Object>> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            if (!StringUtils.hasText(t)) continue;
            out.add(buildClassifyResult(t, "llm"));
        }
        return Result.ok(out);
    }

    // ====================== 场景 1: 客户反馈分析 ======================

    @Operation(summary = "场景一·客户反馈分析",
            description = "输入多条用户评论(每行一条), 返回情感分布计数、Top 抱怨词、Top 表扬词")
    @PostMapping("/feedback/analyze")
    public Result<Map<String, Object>> analyzeFeedback(@RequestBody FeedbackRequest body) {
        if (body == null || body.texts == null) body = new FeedbackRequest();
        List<String> raw = body.texts.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", raw.size());

        if (raw.isEmpty()) {
            resp.put("distribution", Map.of("POSITIVE", 0, "NEUTRAL", 0, "NEGATIVE", 0));
            resp.put("topPositive", List.of());
            resp.put("topNegative", List.of());
            resp.put("samples", List.of());
            return Result.ok(resp);
        }

        // 分布计数
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("POSITIVE", 0);
        dist.put("NEUTRAL", 0);
        dist.put("NEGATIVE", 0);

        // 逐条分类(走 LLM, 中文场景更稳), 同时按 tag 收集关键词
        Map<String, Integer> posKw = new LinkedHashMap<>();
        Map<String, Integer> negKw = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>(raw.size());
        for (String t : raw) {
            Sentiment tag;
            try {
                String raw0 = sentimentAnalyzer.classifyRaw(t);
                tag = sentimentAnalyzer.mapRawToSentiment(raw0);
            } catch (Exception e) {
                log.warn("[反馈分析] 单条分类失败, 默认 NEUTRAL: text={}, err={}", t, e.toString());
                tag = Sentiment.NEUTRAL;
            }
            dist.merge(tag.name(), 1, Integer::sum);

            for (String w : extractKeywords(t)) {
                Map<String, Integer> target = (tag == Sentiment.NEGATIVE) ? negKw : (tag == Sentiment.POSITIVE ? posKw : null);
                if (target != null) target.merge(w, 1, Integer::sum);
            }

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("text", t);
            s.put("tag", tag);
            samples.add(s);
        }

        resp.put("distribution", dist);
        resp.put("positiveRate", percent(dist.get("POSITIVE"), raw.size()));
        resp.put("negativeRate", percent(dist.get("NEGATIVE"), raw.size()));
        resp.put("topPositive", topKeywords(posKw, 8));
        resp.put("topNegative", topKeywords(negKw, 8));
        resp.put("samples", samples);
        return Result.ok(resp);
    }

    // ====================== 场景 2: 社交媒体监控 ======================

    @Operation(summary = "场景二·社交媒体监控",
            description = "分析单条社媒文本的情感 + 严重度 + 命中关键词; 严重度仅在 NEGATIVE 时返回(low/medium/high)")
    @PostMapping("/social/analyze")
    public Result<Map<String, Object>> analyzeSocial(@RequestBody SocialRequest body) {
        if (body == null || !StringUtils.hasText(body.text)) {
            return Result.fail(400, "text 不能为空");
        }
        String text = body.text.trim();

        Sentiment tag;
        try {
            String raw = sentimentAnalyzer.classifyRaw(text);
            tag = sentimentAnalyzer.mapRawToSentiment(raw);
        } catch (Exception e) {
            log.warn("[社交监控] 分类失败, 默认 NEUTRAL: text={}, err={}", text, e.toString());
            tag = Sentiment.NEUTRAL;
        }

        List<String> hits = extractKeywords(text);
        String severity = null;
        if (tag == Sentiment.NEGATIVE) {
            int n = negWeight(hits);
            if (n >= 3) severity = "high";
            else if (n >= 1) severity = "medium";
            else severity = "low";
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("text", text);
        resp.put("platform", body.platform);
        resp.put("sentiment", tag);
        resp.put("severity", severity);
        resp.put("keywords", hits);
        return Result.ok(resp);
    }

    // ====================== 场景 3: 聊天机器人响应 ======================

    @Operation(summary = "场景三·聊天机器人响应建议",
            description = "基于当前用户消息的情感, 返回建议语气 + 模板回复(对话历史仅用于上下文感知, 当前以最新一条 user 消息为准)")
    @PostMapping("/chat/reply")
    public Result<Map<String, Object>> replyChat(@RequestBody ChatReplyRequest body) {
        if (body == null || !StringUtils.hasText(body.message)) {
            return Result.fail(400, "message 不能为空");
        }
        String text = body.message.trim();

        Sentiment tag;
        try {
            String raw = sentimentAnalyzer.classifyRaw(text);
            tag = sentimentAnalyzer.mapRawToSentiment(raw);
        } catch (Exception e) {
            log.warn("[聊天回复] 分类失败, 默认 NEUTRAL: text={}, err={}", text, e.toString());
            tag = Sentiment.NEUTRAL;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sentiment", tag);
        resp.put("tone", suggestTone(tag));
        resp.put("templateReply", templateReply(tag, text));
        resp.put("userMessage", text);
        return Result.ok(resp);
    }

    // ====================== 场景描述 ======================

    @Operation(summary = "三个使用场景描述", description = "页面顶部 banner 用, 返回 3 个场景的标题与说明")
    @GetMapping("/scenarios")
    public Result<List<Map<String, Object>>> scenarios() {
        List<Map<String, Object>> list = List.of(
                Map.of(
                        "key", "feedback",
                        "icon", "ChatLineSquare",
                        "title", "客户反馈分析",
                        "desc", "将客户评论分类为积极、中性或消极"
                ),
                Map.of(
                        "key", "social",
                        "icon", "View",
                        "title", "社交媒体监控",
                        "desc", "分析社交媒体评论中的情感趋势"
                ),
                Map.of(
                        "key", "chat",
                        "icon", "Service",
                        "title", "聊天机器人响应",
                        "desc", "理解用户情感以提供更好的响应"
                )
        );
        return Result.ok(list);
    }

    // ====================== 内部辅助 ======================

    /** 单条分类结果(支持 mode=llm/embedding/both) */
    private Map<String, Object> buildClassifyResult(String text, String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        String m = (mode == null ? "llm" : mode).toLowerCase(Locale.ROOT);
        if (m.equals("embedding") || m.equals("both")) {
            List<Sentiment> tags = embeddingClassifier.classify(text);
            ClassificationResult<Sentiment> scored = embeddingClassifier.classifyWithScores(text);
            Map<String, Double> scores = new LinkedHashMap<>();
            for (ScoredLabel<Sentiment> sl : scored.scoredLabels()) scores.put(sl.label().name(), sl.score());
            out.put("embeddingTags", tags);
            out.put("embeddingScores", scores);
        }
        if (m.equals("llm") || m.equals("both")) {
            String raw = sentimentAnalyzer.classifyRaw(text);
            Sentiment mapped = sentimentAnalyzer.mapRawToSentiment(raw);
            out.put("raw", raw);
            out.put("llmSentiment", mapped);
        }
        return out;
    }

    private void validateText(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("text 不能为空");
        }
    }

    /** 0~100 百分数, 保留 1 位小数 */
    private double percent(int part, int total) {
        if (total <= 0) return 0;
        return Math.round(part * 1000.0 / total) / 10.0;
    }

    /** 从 Map 中取出现次数 Top N 的关键词(已按频率倒序) */
    private List<Map<String, Object>> topKeywords(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("word", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ---------------------- 关键词词典(轻量内置) ----------------------

    /** 褒义词: 用于反馈分析 Top 表扬词 + 聊天语气提示 */
    private static final Set<String> POSITIVE_WORDS = new LinkedHashSet<>(Arrays.asList(
            "满意", "喜欢", "推荐", "棒", "好评", "赞", "给力", "完美", "出色",
            "超预期", "惊喜", "物超所值", "贴心", "细致", "漂亮", "完美", "香",
            "好看", "好用", "划算", "值", "值了", "舒服", "快", "流畅"
    ));

    /** 贬义词: 用于反馈分析 Top 抱怨词 + 社交监控严重度评估 */
    private static final Set<String> NEGATIVE_WORDS = new LinkedHashSet<>(Arrays.asList(
            "失望", "差评", "退货", "垃圾", "坑", "投诉", "烦", "后悔", "差劲",
            "粗糙", "破损", "漏气", "难用", "不会再来", "敷衍", "慢", "卡顿",
            "崩溃", "难看", "异味", "刺鼻", "假货", "掉色", "起球"
    ));

    /** 严重度权重: 部分高频贬词计数翻倍 */
    private static final Set<String> HEAVY_NEG = new LinkedHashSet<>(Arrays.asList(
            "假货", "崩溃", "投诉", "差评", "退货", "垃圾", "坑"
    ));

    /** 简易分词: 把文本切成 2~4 字短语, 与词典匹配; 匹配命中短语的原词 */
    private List<String> extractKeywords(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        // 标点归一化, 再做长度 2~4 的滑动窗口匹配
        String normalized = text.replaceAll("[\\p{Punct}\\s]+", "");
        if (normalized.length() < 2) return List.of();

        Set<String> all = new LinkedHashSet<>();
        all.addAll(POSITIVE_WORDS);
        all.addAll(NEGATIVE_WORDS);

        List<String> hits = new ArrayList<>();
        Pattern p = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]+");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (token.length() < 2 || token.length() > 4) continue;
            if (all.contains(token)) hits.add(token);
        }
        return hits;
    }

    /** NEGATIVE 时按命中贬义词的「权重」算严重度 */
    private int negWeight(List<String> hits) {
        int n = 0;
        for (String w : hits) {
            if (NEGATIVE_WORDS.contains(w)) {
                n += HEAVY_NEG.contains(w) ? 2 : 1;
            }
        }
        return n;
    }

    /** 按情感推荐机器人回复语气 */
    private String suggestTone(Sentiment tag) {
        return switch (tag) {
            case POSITIVE -> "热情、积极; 感谢并邀请复购";
            case NEGATIVE -> "耐心、同理心; 先致歉再提供解决方案";
            case NEUTRAL -> "中性、客观; 主动询问细节或补充信息";
        };
    }

    /** 按情感返回模板回复(可被业务方替换) */
    private String templateReply(Sentiment tag, String userText) {
        return switch (tag) {
            case POSITIVE -> "非常感谢您的好评! 我们会继续保持, 也欢迎下次再来~";
            case NEGATIVE -> "非常抱歉给您带来了不好的体验, 能否详细说明一下问题? 我们会尽快为您处理。";
            case NEUTRAL -> "收到您的反馈了, 请问还有什么具体想了解或反馈的吗?";
        };
    }

    // ====================== 入参 ======================

    /** 批量分类入参 */
    public static class BatchRequest {
        public List<String> texts;
    }

    /** 客户反馈分析入参: texts 多行评论 */
    public static class FeedbackRequest {
        public List<String> texts;
    }

    /** 社交媒体监控入参: text + 可选 platform */
    public static class SocialRequest {
        public String text;
        public String platform;
    }

    /** 聊天回复建议入参: 当前用户消息 + 对话历史(可选, 当前仅用于上下文展示) */
    public static class ChatReplyRequest {
        public String message;
        public List<Map<String, String>> history;
    }

    /** 兼容: 强制要求登录(Sa-Token 拦截器会处理, 这里用一行日志便于排查) */
    private Long currentUserId() {
        long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        log.debug("[情感分析] 当前用户 id={}", uid);
        return uid;
    }
}
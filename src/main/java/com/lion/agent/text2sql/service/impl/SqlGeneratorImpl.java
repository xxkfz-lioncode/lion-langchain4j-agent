package com.lion.agent.text2sql.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lion.agent.ai.observability.LlmCallContext;
import com.lion.agent.text2sql.service.FewshotStore;
import com.lion.agent.text2sql.service.SqlGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 生成实现。
 * <p>
 * 骨架阶段: 单阶段 NL→SQL, JSON 结构化输出 + 容错解析。
 * 后续增强: 两阶段(先结构化解析指标/维度 → 再生成)、JSON_SCHEMA 严格模式。
 */
@Slf4j
@Service
public class SqlGeneratorImpl implements SqlGenerator {

    private static final String PROMPT_TEMPLATE = "prompts/text2sql-system.txt";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;

    public SqlGeneratorImpl(@Qualifier("openAiChatModel") ChatModel chatModel,
                            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.promptTemplate = loadPromptTemplate();
    }

    @Override
    public SqlGenerationResult generate(String question, String schemaDigest,
                                        List<String> termsHit, List<FewshotStore.Example> fewshots,
                                        Long userId) {
        return doGenerate(question, schemaDigest, termsHit, fewshots, null, null, userId);
    }

    @Override
    public SqlGenerationResult regenerate(String question, String schemaDigest,
                                          List<String> termsHit, List<FewshotStore.Example> fewshots,
                                          String previousSql, String errorMessage) {
        return doGenerate(question, schemaDigest, termsHit, fewshots, previousSql, errorMessage, null);
    }

    private SqlGenerationResult doGenerate(String question, String schemaDigest,
                                           List<String> termsHit, List<FewshotStore.Example> fewshots,
                                           String previousSql, String errorMessage,
                                           Long userId) {
        if (!StringUtils.hasText(schemaDigest)) {
            log.warn("[text2sql] schema digest 为空, 无法生成 SQL");
            return SqlGenerationResult.clarify("暂未找到与您问题相关的数据表, 请换个说法或联系管理员初始化数据模型。");
        }

        String userPrompt = buildUserPrompt(question, schemaDigest, termsHit, fewshots,
                previousSql, errorMessage);

        // 阻塞式调用: try/finally 中 begin/end, 使 LLM 调用日志带上 userId
        String memoryId = "chat:" + (userId == null ? 0 : userId) + ":text2sql";
        LlmCallContext.begin(memoryId);
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            new SystemMessage(promptTemplate),
                            new UserMessage(userPrompt)))
                    .build();
            ChatResponse response = chatModel.chat(request);
            String text = response.aiMessage().text();
            log.info("[text2sql] LLM 原始输出: {}", StringUtils.hasText(text) ? text : "<empty>");
            return parse(text);
        } catch (Exception e) {
            log.error("[text2sql] LLM 生成失败: {}", e.getMessage(), e);
            return SqlGenerationResult.clarify("AI 生成 SQL 失败, 请稍后重试。");
        } finally {
            LlmCallContext.end(memoryId);
        }
    }

    /**
     * 拼装用户 prompt。
     */
    private String buildUserPrompt(String question, String schemaDigest,
                                   List<String> termsHit, List<FewshotStore.Example> fewshots,
                                   String previousSql, String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【相关表结构】\n").append(schemaDigest).append("\n\n");
        sb.append("【本次问题】").append(question).append("\n");

        // 术语注入
        if (termsHit != null && !termsHit.isEmpty()) {
            sb.append("【命中术语】").append(String.join("、", termsHit)).append("\n");
        }
        if (fewshots != null && !fewshots.isEmpty()) {
            sb.append("【参考示例】\n");
            for (FewshotStore.Example e : fewshots) {
                sb.append("  问: ").append(e.question()).append("\n")
                        .append("  SQL: ").append(e.sql()).append("\n")
                        .append("  口径: ").append(e.explanation() == null ? "" : e.explanation()).append("\n");
            }
        }
        if (StringUtils.hasText(previousSql)) {
            sb.append("【上一次生成的 SQL】\n").append(previousSql).append("\n");
        }
        if (StringUtils.hasText(errorMessage)) {
            sb.append("【执行报错】\n").append(errorMessage).append("\n");
        }
        sb.append("\n请只输出 JSON。");
        return sb.toString();
    }

    /**
     * 容错解析 LLM 输出: 去掉 markdown 代码块 → 截取第一个完整 JSON 对象 → 反序列化。
     */
    private SqlGenerationResult parse(String text) {
        if (!StringUtils.hasText(text)) {
            return SqlGenerationResult.clarify("AI 未返回内容, 请重试。");
        }
        String json = extractJson(text);
        try {
            JsonNode node = objectMapper.readTree(json);
            String sql = node.path("sql").asText(null);
            String explanation = node.path("explanation").asText("");
            String chartType = StringUtils.hasText(node.path("chartType").asText())
                    ? node.path("chartType").asText() : "TABLE";
            String clarification = node.path("clarification").asText("");
            String intent = StringUtils.hasText(node.path("intent").asText())
                    ? node.path("intent").asText() : "QUERY";

            if (StringUtils.hasText(clarification)) {
                return new SqlGenerationResult(null, explanation, chartType, clarification, intent);
            }
            if (!StringUtils.hasText(sql)) {
                return SqlGenerationResult.clarify("AI 未能生成 SQL, 请换个说法描述您的问题。");
            }
            return new SqlGenerationResult(sql, explanation, chartType, "", intent);
        } catch (Exception e) {
            log.warn("[text2sql] LLM 输出解析失败, 原文={}", text);
            return SqlGenerationResult.clarify("AI 返回格式异常, 请重试。");
        }
    }

    /**
     * 从可能带 ```json 包裹/前后废话的文本中截取 JSON 对象。
     */
    private String extractJson(String text) {
        String s = text.trim();
        // 去掉 ```json ... ``` 或 ``` ... ```
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        s = s.trim();
        // 截取第一个 { 到最后一个 } 之间的内容
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /**
     * 加载系统 prompt 模板(仅含规则与输出格式; schema/术语/示例在用户 prompt 中按需注入)。
     */
    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE);
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            // 模板中的占位段落替换为说明, 具体内容由 buildUserPrompt 注入
            return raw.replace("{terms}", "(见本次问题中的【命中术语】)")
                    .replace("{schema}", "(见本次问题中的【相关表结构】)")
                    .replace("{fewshots}", "(见本次问题中的【参考示例】)");
        } catch (Exception e) {
            log.warn("[text2sql] prompt 模板加载失败, 使用内置默认: {}", e.getMessage());
            return "你是资深数据分析专家, 只生成 MySQL 只读 SELECT 语句。"
                    + "输出 JSON: {sql, explanation, chartType, clarification, intent}";
        }
    }
}

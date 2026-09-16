package com.lion.agent.structured.extractor;

import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.structured.StructuredOutputService;
import com.lion.agent.structured.StructuredOutputStrategy;
import com.lion.agent.structured.StructuredTarget;
import com.lion.agent.structured.parser.JsonOutputParser;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 方式一: JSON Schema(最可靠)
 * <p>
 * 把目标类型的 JSON Schema 塞进 {@code response_format},
 * 由模型/供应商在解码阶段直接约束输出, 绝大多数情况下能稳定拿到符合结构的结果。
 * <p>
 * 支持方(官方): OpenAI、Azure OpenAI、Amazon Bedrock、Google AI Gemini、Mistral、Ollama 等。
 * 项目当前用的千问(DashScope OpenAI 兼容模式)对 json_object 支持稳定,
 * 对 json_schema 则按模型实测 —— 若模型不支持, 接口会 400,
 * 此时可用 {@link StructuredOutputService#extractWithFallback} 自动降到方式二。
 */
@Slf4j
@Component
public class JsonSchemaExtractor implements StructuredOutputExtractor {

    /** 提示词很短, 因为结构约束已交给 schema */
    private static final String SYSTEM_PROMPT = "你是信息抽取器, 只输出 JSON, 不要任何解释或额外文字。";

    private final ChatModel chatModel;

    public JsonSchemaExtractor(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public StructuredOutputStrategy strategy() {
        return StructuredOutputStrategy.JSON_SCHEMA;
    }

    @Override
    public <T> T extract(String input, StructuredTarget<T> target) {
        if (!target.hasJsonSchema()) {
            throw new BusinessException("方式一(JSON Schema)需要 JsonSchema, 目标类型: " + target.typeName());
        }

        // 1. response_format = { "type": "JSON", "json_schema": { ... } }
        ResponseFormat responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(target.jsonSchema())
                .build();

        // 2. 正常带 system / user 消息, 只是多了一份结构约束
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(input)))
                .responseFormat(responseFormat)
                .build();

        // 3. 调用模型 -> 文本 -> 反序列化
        ChatResponse response = chatModel.chat(request);
        String raw = response.aiMessage().text();
        log.info("[结构化输出-JSON Schema] 原始输出: {}", raw);
        return JsonOutputParser.parse(raw, target.type());
    }
}
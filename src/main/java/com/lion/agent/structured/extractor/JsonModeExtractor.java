package com.lion.agent.structured.extractor;

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
 * 方式二: 提示词 + JSON Mode
 * <p>
 * 请求里只声明 {@code response_format = json_object} —— 模型侧保证输出是合法 JSON,
 * 但<b>不保证</b>字段名/类型完全符合预期, 因此把字段约定写在提示词里。
 * <p>
 * 注意: OpenAI/千问的 json_object 模式要求提示词里出现 "JSON" 字样(否则报错),
 * 下面的 prompt 已包含该字样。
 * <p>
 * 适用: 项目当前用的千问(DashScope OpenAI 兼容模式)对 json_object 支持稳定,
 * 因此方式二实际是项目里的"主力"方式。
 */
@Slf4j
@Component
public class JsonModeExtractor implements StructuredOutputExtractor {

    private final ChatModel chatModel;

    public JsonModeExtractor(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public StructuredOutputStrategy strategy() {
        return StructuredOutputStrategy.JSON_MODE;
    }

    @Override
    public <T> T extract(String input, StructuredTarget<T> target) {
        String systemPrompt = """
                你是信息抽取器。请从用户提供的文本里抽取信息, 并按下面的字段说明输出一个 JSON 对象。
                只输出 JSON 对象本身, 不要 markdown 代码块, 不要任何解释或额外文字。

                字段说明:
                %s
                """.formatted(target.fieldGuide());

        ResponseFormat responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .build();

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from(systemPrompt), UserMessage.from(input)))
                .responseFormat(responseFormat)
                .build();

        ChatResponse response = chatModel.chat(request);
        String raw = response.aiMessage().text();
        log.info("[结构化输出-JSON Mode] 原始输出: {}", raw);
        return JsonOutputParser.parse(raw, target.type());
    }
}
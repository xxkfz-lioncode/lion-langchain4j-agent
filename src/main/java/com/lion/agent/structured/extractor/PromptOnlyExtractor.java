package com.lion.agent.structured.extractor;

import com.lion.agent.structured.StructuredOutputService;
import com.lion.agent.structured.StructuredOutputStrategy;
import com.lion.agent.structured.StructuredTarget;
import com.lion.agent.structured.parser.JsonOutputParser;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 方式三: 纯提示词
 * <p>
 * 请求里完全不设 {@code response_format}, 结构约束全交给提示词 —— 可靠性最低,
 * 模型可能输出"好的, 抽取结果如下: { ... }"这种带说明文字的回答,
 * 也可能漏字段或自由发挥。
 * <p>
 * 适用: 仅用于对照三种方式的差异; 实际业务几乎不直接用,
 * 真正需要"纯提示词"时也会被 {@link StructuredOutputService#extractWithFallback}
 * 当作最差情况下的保底。
 */
@Slf4j
@Component
public class PromptOnlyExtractor implements StructuredOutputExtractor {

    private final ChatModel chatModel;

    public PromptOnlyExtractor(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public StructuredOutputStrategy strategy() {
        return StructuredOutputStrategy.PROMPT_ONLY;
    }

    @Override
    public <T> T extract(String input, StructuredTarget<T> target) {
        String systemPrompt = """
                你是信息抽取器。请从用户文本里抽取信息, 并按下面的字段说明输出。
                输出要求: 严格输出一个 JSON(以 { 开头, 以 } 结尾), 不要 markdown 代码块, 不要解释文字。

                字段说明:
                %s
                """.formatted(target.fieldGuide());

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(SystemMessage.from(systemPrompt), UserMessage.from(input)))
                .build();

        ChatResponse response = chatModel.chat(request);
        String raw = response.aiMessage().text();
        log.info("[结构化输出-Prompt Only] 原始输出: {}", raw);
        return JsonOutputParser.parse(raw, target.type());
    }
}
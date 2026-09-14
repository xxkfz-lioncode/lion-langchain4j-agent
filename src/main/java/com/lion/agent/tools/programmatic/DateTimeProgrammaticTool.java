package com.lion.agent.tools.programmatic;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 编程式"获取当前时间"工具。
 * <p>
 * 与注解式 {@code DateTools} 的对比:
 * <ul>
 *   <li>{@code DateTools} 是 {@code @Tool} 注解类, 给 {@code AnnotatedAssistant} 用;</li>
 *   <li>本类是 {@link ProgrammaticTool} 实现, 给 {@code ManualAssistant} 用。</li>
 * </ul>
 * 两个工具的业务输出完全一致, 只是注册方式不同 —— 演示两种写法并存。
 */
@Slf4j
@Component
public class DateTimeProgrammaticTool implements ProgrammaticTool {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ToolSpecification spec() {
        // 无参工具也要给空对象 schema: 只传 name/description 时, 部分模型会报参数格式不合法
        return ToolSpecification.builder()
                .name("getCurrentTime")
                .description("获取当前的日期和时间, 返回格式为 yyyy-MM-dd HH:mm:ss")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String now = LocalDateTime.now().format(TIME_FORMAT);
        log.info("[编程式工具] {}: 返回 {}", request.name(), now);
        return now;
    }
}
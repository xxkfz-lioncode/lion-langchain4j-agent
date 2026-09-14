package com.lion.agent.controller.test;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Tag(name = "测试-手动工具循环", description = "不经 AiServices, 手动 ChatRequest + 工具调用循环的演示(免登录)")
@Slf4j
@RestController
public class WeatherController {

    /** 最大工具调用轮次，防止 LLM 死循环调用 */
    private static final int MAX_TOOL_ITERATIONS = 5;

    private final ChatModel chatModel;

    public WeatherController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Operation(summary = "查询伦敦天气(手动 function calling 演示)",
            description = "演示不使用 AiServices 时的原生工具调用循环: 发送消息 → 模型请求工具 → 执行工具回填 → 模型给出最终回答")
    @GetMapping("/weather")
    public String askWeather() {
        // 1. 定义工具规范
        ToolSpecification weatherSpec = buildWeatherSpec();

        // 2. 工具名 -> 执行函数 的映射表（参数类型改为 Hutool 的 JSONObject）
        Map<String, Function<JSONObject, String>> toolRegistry = Map.of(
                "getWeather", this::getWeather
        );

        // 3. 初始化消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("What will the weather be like in London tomorrow?"));

        // 4. 工具调用循环
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(List.of(weatherSpec))
                    .build();

            ChatResponse response = chatModel.chat(request);
            AiMessage aiMessage = response.aiMessage();

            // 模型未请求工具，直接返回文本
            if (!aiMessage.hasToolExecutionRequests()) {
                log.info("LLM 直接回答：{}", aiMessage.text());
                return aiMessage.text();
            }

            log.info("LLM 决定调用工具，共 {} 个请求", aiMessage.toolExecutionRequests().size());
            messages.add(aiMessage);

            // 5. 逐个执行工具调用
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                String result = executeToolSafely(toolRequest, toolRegistry);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }
        }

        log.warn("达到最大工具调用轮次 {}，强制终止", MAX_TOOL_ITERATIONS);
        return "抱歉，处理您的请求时超出了工具调用次数限制。";
    }

    /**
     * 构建天气工具的 ToolSpecification
     */
    private ToolSpecification buildWeatherSpec() {
        return ToolSpecification.builder()
                .name("getWeather")
                .description("Returns the weather forecast for a given city")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city", "The city name, e.g. London")
                        .required("city")
                        .build())
                .build();
    }

    /**
     * 安全执行工具：处理工具名幻觉和参数解析异常
     */
    private String executeToolSafely(ToolExecutionRequest request,
                                     Map<String, Function<JSONObject, String>> registry) {
        log.info("工具调用 -> name={}, arguments={}", request.name(), request.arguments());

        Function<JSONObject, String> tool = registry.get(request.name());
        if (tool == null) {
            String msg = "Unknown tool: " + request.name();
            log.warn(msg);
            return msg;
        }

        try {
            // Hutool 解析 JSON 字符串为 JSONObject
            JSONObject args = JSONUtil.parseObj(request.arguments());
            return tool.apply(args);
        } catch (Exception e) {
            String msg = "Tool execution failed: " + e.getMessage();
            log.error(msg, e);
            return msg;
        }
    }

    /**
     * 真实的工具实现：解析参数并返回结果
     */
    private String getWeather(JSONObject args) {
        // Hutool 取值：getStr 返回字符串，缺省参数时返回 null
        String city = args.getStr("city");
        // 实际项目中此处应调用天气 API
        return "The weather tomorrow in " + city + " is 25°C and sunny.";
    }
}

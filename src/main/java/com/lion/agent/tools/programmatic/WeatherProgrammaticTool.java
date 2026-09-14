package com.lion.agent.tools.programmatic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lion.agent.tools.WeatherApiClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 编程式"查询天气"工具: 复用 {@link WeatherApiClient}(带熔断), 因此 {@code @CircuitBreaker}
 * 等 AOP 切面在外部依赖侧照旧生效。
 * <p>
 * 与注解式 {@code WeatherTools} 的区别只是注册方式:
 * <ul>
 *   <li>{@code WeatherTools} 用 {@code @Tool("...")} 注解方法, 由 LangChain4j starter 反射扫描;</li>
 *   <li>本类用 {@link ProgrammaticTool} 显式给出 spec + executor, 由 {@link ProgrammaticToolsFactory}
 *       收集后交给 {@code AiServices}。</li>
 * </ul>
 * 入参约定: 模型会按 {@link #spec()} 中声明的参数 schema 生成 JSON 入参, 这里统一通过
 * {@link ProgrammaticTool#stringArgument} 取值, 缺参/非法一律返回 null, 由本类决定兜底文案。
 */
@Slf4j
@Component
public class WeatherProgrammaticTool implements ProgrammaticTool {

    private final WeatherApiClient weatherApiClient;
    private final ObjectMapper objectMapper;

    public WeatherProgrammaticTool(WeatherApiClient weatherApiClient, ObjectMapper objectMapper) {
        this.weatherApiClient = weatherApiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolSpecification spec() {
        return ToolSpecification.builder()
                .name("getTodayWeather")
                .description("查询指定城市的今日天气(模拟数据)")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city", "城市名称, 如: 北京")
                        .required("city")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String city = ProgrammaticTool.stringArgument(request, "city", objectMapper);
        if (city == null || city.isBlank()) {
            return "未提供城市名, 请告诉我您想查询哪个城市的天气。";
        }
        log.info("[编程式工具] {}: city={}, memoryId={}", request.name(), city, memoryId);
        // 走到外部依赖客户端, 熔断/降级发生在这里
        return weatherApiClient.queryTodayWeather(city.trim());
    }
}
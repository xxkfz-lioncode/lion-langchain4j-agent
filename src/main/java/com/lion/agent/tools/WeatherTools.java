package com.lion.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * 天气工具(供大模型 function calling 调用)。
 * <p>
 * 本类刻意<b>不加</b> {@code @CircuitBreaker} 等 AOP 注解：带 AOP 注解的 Bean 会被 Spring 用
 * CGLIB 代理，而 LangChain4j 反射扫描 @Tool 注解注册工具时可能扫不到代理子类的方法，导致工具失效。
 * 熔断保护加在真正的外部依赖客户端 {@link WeatherApiClient} 上。
 */
@Component
public class WeatherTools {

    private final WeatherApiClient weatherApiClient;

    public WeatherTools(WeatherApiClient weatherApiClient) {
        this.weatherApiClient = weatherApiClient;
    }

    /**
     * 查询指定城市今天的天气情况
     *
     * @param city 城市名称, 如: 北京
     * @return 天气描述文本, 直接供大模型组织回答
     */
    @Tool("查询指定城市的今日天气(模拟数据)")
    public String getTodayWeather(@ToolMemoryId String memoryId, @P("城市名称, 如: 北京") String city) {
        if (city == null || city.isBlank()) {
            return "未提供城市名, 请告诉我您想查询哪个城市的天气。";
        }
        // 经 Spring 代理调用外部接口客户端, 熔断/降级发生在这里
        return weatherApiClient.queryTodayWeather(city.trim());
    }
}

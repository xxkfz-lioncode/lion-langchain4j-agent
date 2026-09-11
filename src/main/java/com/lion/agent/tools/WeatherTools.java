package com.lion.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 获取天气工具
 */
@Component
public class WeatherTools {

    private static final String[] CONDITIONS = {"晴", "晴转多云", "多云", "阴", "小雨", "阵雨", "中雨", "雷阵雨"};

    /**
     * 查询指定城市今天的天气情况
     *
     * @param city 城市名称, 如: 北京
     * @return 天气描述文本, 直接供大模型组织回答
     */
    @Tool("查询指定城市的今日天气(模拟数据)")
    public String getTodayWeather(@P("城市名称, 如: 北京") String city) {
        if (city == null || city.isBlank()) {
            return "未提供城市名, 请告诉我您想查询哪个城市的天气。";
        }
        String name = city.trim();
        Random random = new Random(name.toLowerCase().hashCode());

        String condition = CONDITIONS[random.nextInt(CONDITIONS.length)];
        int min = 15 + random.nextInt(10);
        int max = min + 6 + random.nextInt(8);
        int current = min + random.nextInt(max - min + 1);
        int humidity = 40 + random.nextInt(55);
        int wind = 5 + random.nextInt(25);

        return String.format("%s今日天气: %s, 气温 %d~%d°C, 当前 %d°C, 湿度 %d%%, 风力 %dkm/h(模拟数据, 仅供参考)。",
                name, condition, min, max, current, humidity, wind);
    }
}

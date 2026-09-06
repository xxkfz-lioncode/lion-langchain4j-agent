package com.lion.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 智能助手工具集
 * <p>
 * 方法通过 {@link Tool} 注解声明, 由大模型在对话中根据用户意图自主决定是否调用。
 * 该类作为 Spring Bean 注册, 无状态, 可安全被并发调用。
 */
@Component
public class AgentTools {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 获取当前时间(无参数工具示例) */
    @Tool("获取当前的日期和时间, 返回格式为 yyyy-MM-dd HH:mm:ss")
    public String currentTime() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    /** 整数加法(带参数工具示例) */
    @Tool("计算两个整数的和")
    public int add(int a, int b) {
        return a + b;
    }
}

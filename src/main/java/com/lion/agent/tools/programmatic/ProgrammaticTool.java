package com.lion.agent.tools.programmatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * 编程式(手工)工具的统一接口: 给定 {@link #spec()} 给模型看, 给定 {@link #execute} 真正执行。
 * <p>
 * 与注解式工具({@code @Tool} 注解)的关系:
 * <ul>
 *   <li>注解式: 在工具类方法上加 {@code @Tool("描述")}, LangChain4j starter 自动扫到并生成
 *       {@link ToolSpecification}, 反射调用方法, 写起来零代码;</li>
 *   <li>编程式(本接口): 由本类显式给出 {@link ToolSpecification} 的名字/描述/参数 schema,
 *       实现 {@link ToolExecutor#execute} 写真正执行逻辑。优势是参数解析/限流/日志/动态启停等
 *       横切逻辑可控, 不会被 AOP 代理干扰。</li>
 * </ul>
 * Bean 本身即 {@link ToolExecutor}, 装配时直接 {@code tool.spec() -> tool} 注册即可,
 * 无需额外 lambda 适配。
 */
public interface ProgrammaticTool extends ToolExecutor {

    /**
     * 工具描述(给模型看): 名字、描述、参数 schema。
     * <p>
     * 同一个工具名只能注册一次, 重复会抛异常(框架层校验)。
     */
    ToolSpecification spec();

    /**
     * 从模型生成的入参 JSON 中按名字取字符串参数。
     * <p>
     * 为什么需要这层兜底:
     * <ul>
     *   <li>模型可能漏传、传错类型, 甚至生成非法 JSON;</li>
     *   <li>直接抛异常会被框架包装成错误结果回填给模型, 不如自己处理语义更清晰
     *       (比如返回"请告诉我城市名"而非"参数解析失败");</li>
     *   <li>统一在此收敛解析逻辑, 多个工具不再各自重复 try/catch。</li>
     *
     * @param request      模型发出的工具调用请求
     * @param name         参数名
     * @param objectMapper 用于解析 JSON 入参
     * @return 字符串值; 参数缺失/类型不符/JSON 非法时一律返回 {@code null}, 由调用方决定后续文案
     */
    static String stringArgument(ToolExecutionRequest request, String name, ObjectMapper objectMapper) {
        String arguments = request.arguments();
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(arguments).path(name);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
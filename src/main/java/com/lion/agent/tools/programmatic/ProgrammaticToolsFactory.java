package com.lion.agent.tools.programmatic;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编程式工具装配工厂: 集中管理所有 {@link ProgrammaticTool} Bean, 输出统一的
 * {@code Map<ToolSpecification, ToolExecutor>}, 供 {@code ManualAssistant} 在启动期一次性注入。
 * <p>
 * 装配流程(在 {@link PostConstruct} 启动期一次性完成):
 * <ol>
 *   <li>Spring 注入全部 {@link ProgrammaticTool} Bean(只要实现该接口即被自动收集);</li>
 *   <li>按 Bean 注入顺序组装成 {@link LinkedHashMap}, 顺序稳定便于排查
 *       (部分模型对工具顺序敏感);</li>
 *   <li>启动日志打印已注册的工具名 + 总数, 方便核对加载结果。</li>
 * </ol>
 * <p>
 * 与 {@link com.lion.agent.assistant.ManualAssistant} 的协作:
 * <pre>
 *   Map&lt;ToolSpecification, ToolExecutor&gt; tools = toolsFactory.getTools();
 *   AiServices.builder(ProgrammaticAssistant.class)
 *       ...
 *       .tools(tools)                            // 编程式注册工具: spec -> executor
 *       .build();
 * </pre>
 * <p>
 * 与 {@link com.lion.agent.skills.SkillsFactory} 的对称性:
 * <ul>
 *   <li>{@code SkillsFactory} 输出 {@code Skills}(Skill 框架自己注入的工具);</li>
 *   <li>{@code ProgrammaticToolsFactory} 输出 {@code Map<ToolSpecification, ToolExecutor>}
 *       (手写 spec + executor);</li>
 *   <li>两者启动期一次性装配, 装配完只读, 调用方拿现成结果, 不重复写散落的代码。</li>
 * </ul>
 * <p>
 * 设计要点:
 * <ul>
 *   <li>装配在 {@code @PostConstruct} 一次性完成, Map 是不可变的视图, 后续只读不写;</li>
 *   <li>新增工具只需新增一个 {@link ProgrammaticTool} 实现类并标 {@code @Component},
 *       工厂会自动收集, 调用方无感;</li>
 *   <li>工具顺序通过 {@link org.springframework.core.annotation.Order} 或 Bean 注册顺序控制,
 *       顺序变化时只影响本类的遍历结果, 不影响功能正确性。</li>
 * </ul>
 */
@Getter
@Slf4j
@Component
public class ProgrammaticToolsFactory {

    /** 装配好的 spec -> executor 映射, 启动后只读
     * -- GETTER --
     *  获取已装配好的编程式工具映射。
     *  <p>
     *  注意: 返回的是同一份不可变视图, 不要尝试修改其内容。
     */
    private Map<ToolSpecification, ToolExecutor> tools;

    /** 所有编程式工具 Bean, 启动期由 Spring 注入 */
    private final List<ProgrammaticTool> programmaticTools;

    public ProgrammaticToolsFactory(List<ProgrammaticTool> programmaticTools) {
        this.programmaticTools = programmaticTools;
    }

    /**
     * 启动期一次性完成工具映射装配。纯内存操作, 不会发起任何外部请求。
     */
    @PostConstruct
    void init() {
        // LinkedHashMap 保留 Bean 注入顺序, 部分模型对工具顺序敏感, 顺序稳定便于排查
        Map<ToolSpecification, ToolExecutor> map = new LinkedHashMap<>();
        for (ProgrammaticTool tool : programmaticTools) {
            ToolSpecification spec = tool.spec();
            // 防御: 同一工具名重复注册时给出明确报错信息, 避免被框架层的报错掩盖
            if (map.containsKey(spec)) {
                throw new IllegalStateException(
                        "编程式工具名重复: " + spec.name()
                                + "(已注册实现: " + map.get(spec).getClass().getSimpleName()
                                + ", 冲突实现: " + tool.getClass().getSimpleName() + ")");
            }
            map.put(spec, tool);
        }
        this.tools = map;
        log.info("[编程式工具装配] 共注册 {} 个工具: {}",
                tools.size(),
                tools.keySet().stream().map(ToolSpecification::name).toList());
    }
}
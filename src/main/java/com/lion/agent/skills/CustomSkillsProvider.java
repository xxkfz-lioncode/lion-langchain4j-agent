package com.lion.agent.skills;

import dev.langchain4j.skills.ActivateSkillToolConfig;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.DefaultSkillResource;
import dev.langchain4j.skills.ReadResourceToolConfig;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.SkillResource;

import java.util.List;
import java.util.function.Function;

/**
 * 自定义 Skill 提供器: 用 {@link dev.langchain4j.skills.DefaultSkill.Builder} 编程式构造 Skill,
 * 同时定义两个工具的配置(激活 Skill / 读取资源)。
 * <p>
 * 与 {@link SkillsFactory} 的协作:
 * <ul>
 *   <li>{@link #customSkills()} — 返回内存构造的 Skill 列表, 由工厂和 classpath 加载的 Skill 合并;</li>
 *   <li>{@link #activateSkillToolConfig()} / {@link #readResourceToolConfig()} — 工具级配置,
 *       工厂统一应用到最终 {@code Skills} 对象上。</li>
 * </ul>
 * <p>
 * 接入到 {@code AiServices} 的方式不变(见 {@code assistant/ManualAssistant.java}):
 * <pre>
 *   .toolProvider(skills.toolProvider())   // 关键: 注入 Skill 的动态工具提供者
 * </pre>
 * <p>
 * 关键点:
 * <ul>
 *   <li>本类只暴露"零件"(Skill 列表 + 两份配置), 不直接构造 Skills; 装配由 {@link SkillsFactory} 统一负责,
 *       避免和 classpath 加载流程产生分支。</li>
 *   <li>{@code activateSkillToolConfig} 控制"激活 Skill"工具的 name / 参数名 / 描述 / 异常策略;
 *       改 name 会改变模型看到的工具名, 改 parameterName 会影响模型传参的字段名(默认 {@code skill_name})。</li>
 *   <li>{@code readResourceToolConfig} 的 {@code relativePathParameterDescriptionProvider}
 *       是一个 {@code Function<List<Skill>, String>} lambda, 可基于当前可用 Skill 动态拼出
 *       "可读取哪些资源"的描述, 比静态描述更精确。</li>
 *   <li>{@link DefaultSkill.Builder#resources(Collection)} 与 {@link DefaultSkill.Builder#tools(Object...)}
 *       互不冲突: resources 是被读取的纯文本资料, tools 是该 Skill 专属的可执行工具。</li>
 * </ul>
 */
public final class CustomSkillsProvider {

    private CustomSkillsProvider() {
    }

    /**
     * 自定义 Skill 列表(全部在内存里构造, 不依赖 classpath / 文件系统资源)。
     *
     * @return 不可变列表, 可与 {@code ClassPathSkillLoader.loadSkills(...)} 合并
     */
    public static List<Skill> customSkills() {
        return List.of(buildDocSummarizer(), buildCodeReviewer());
    }

    /**
     * 自定义"激活 Skill"工具配置: 改名 + 改参数名 + 中文描述 + 严格模式。
     */
    public static ActivateSkillToolConfig activateSkillToolConfig() {
        return ActivateSkillToolConfig.builder()
                .name("enable_skill")                            // 默认: activate_skill
                .description("启用指定 Skill。启用后该 Skill 的专属工具/资源才对本轮对话可见, "
                        + "可读取资源前请先激活对应 Skill。")
                .parameterName("skill")                          // 默认: skill_name(模型传参的字段名)
                .parameterDescription("要启用的 Skill 名称, 必须是系统消息中列出的可用 Skill 之一")
                .throwToolArgumentsExceptions(true)              // 参数非法时抛 ToolArgumentsException(默认 false)
                .build();
    }

    /**
     * 自定义"读取 Skill 资源"工具配置: 改工具名 + 提供动态描述。
     * <p>
     * Provider 入参是当前可用 Skill 列表, 返回值会作为 {@code relative_path} 参数的描述,
     * 让模型知道能传哪些相对路径。比写死静态描述更准确。
     */
    public static ReadResourceToolConfig readResourceToolConfig() {
        Function<List<? extends Skill>, String> dynamicDesc = skills ->
                "可读取的相对路径列表: " + skills.stream()
                        .flatMap(s -> s.resources().stream())
                        .map(SkillResource::relativePath)
                        .toList();

        return ReadResourceToolConfig.builder()
                .name("load_skill_doc")                  // 默认: read_skill_resource
                .description("读取指定 Skill 目录下的文档资源(只读, 不执行)")
                .skillNameParameterName("skill")         // 默认: skill_name
                .skillNameParameterDescription("要读取的 Skill 名称")
                .relativePathParameterName("path")       // 默认: relative_path
                // 静态描述置空, 完全交给 Provider 动态生成; 二者同时存在时静态优先。
                .relativePathParameterDescription(null)
                .relativePathParameterDescriptionProvider(dynamicDesc)
                .build();
    }

    /** Skill 1: 文本摘要 —— 把长文本归纳成 3-5 个要点 */
    private static Skill buildDocSummarizer() {
        return DefaultSkill.builder()
                .name("doc-summarizer")
                .description("把用户给出的长文本归纳成 3-5 个要点")
                .content("""
                        # Doc Summarizer

                        ## 流程
                        1. 切分长文本为段落(每段不超过 500 字)
                        2. 每段提炼 1 个核心观点
                        3. 合并去重, 输出 3-5 条带编号的要点
                        4. 保留关键术语与原文引用
                        """)
                .build();
    }

    /**
     * Skill 2: 代码审核 —— 演示 Skill 带 references 资源。
     * <p>
     * 流程中提到"读取 references/conventions.md", 该路径对应下面构造的 {@link DefaultSkillResource},
     * 框架会以 .content() 字符串作为读取结果返回, 不需要真正落盘。
     */
    private static Skill buildCodeReviewer() {
        SkillResource reference = DefaultSkillResource.builder()
                .relativePath("references/conventions.md")
                .content("""
                        # 命名约定
                        - 类名 UpperCamelCase
                        - 方法/变量 lowerCamelCase
                        - 常量 UPPER_SNAKE_CASE
                        """)
                .build();

        return DefaultSkill.builder()
                .name("code-reviewer")
                .description("按团队约定审阅代码变更, 输出问题清单与改进建议")
                .content("""
                        # Code Reviewer

                        ## 流程
                        1. 调用 load_skill_doc 读取 references/conventions.md 拿到当前约定
                        2. 逐文件审视: 命名 / 异常处理 / 并发安全 / 可测试性
                        3. 按严重程度(必须改 / 建议改 / 可选)分类输出
                        """)
                .resources(List.of(reference))
                .build();
    }
}
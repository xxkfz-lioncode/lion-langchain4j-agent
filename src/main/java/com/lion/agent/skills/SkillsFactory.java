package com.lion.agent.skills;

import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 装配工厂: 集中管理 classpath 加载 / 自定义 Skill / 工具级配置, 输出统一的 {@link Skills} 实例。
 * <p>
 * 装配流程(在 {@link PostConstruct} 启动期一次性完成):
 * <ol>
 *   <li>{@code ClassPathSkillLoader.loadSkills("skills")} 加载 classpath 下的全部 Skill
 *       (即 {@code src/main/resources/skills/&lt;skill-name&gt;/SKILL.md});</li>
 *   <li>{@link CustomSkillsProvider#customSkills()} 拿到内存构造的自定义 Skill;</li>
 *   <li>两份 Skill 合并, 通过 {@link Skills.Builder} 装配, 工具级配置(激活 / 读取资源)统一应用;</li>
 *   <li>启动日志打印每个来源的 Skill 名 + 总数, 方便核对加载结果。</li>
 * </ol>
 * <p>
 * 与 {@link com.lion.agent.assistant.ManualAssistant} 的协作:
 * <pre>
 *   Skills skills = skillsFactory.getSkills();
 *   AiServices.builder(ProgrammaticAssistant.class)
 *       ...
 *       .toolProvider(skills.toolProvider())   // 一次性注入, 替代过去的 .toolProviders(a, b)
 *       .build();
 * </pre>
 * <p>
 * 设计要点:
 * <ul>
 *   <li>装配在 {@code @PostConstruct} 一次性完成, {@code Skills} 是不可变的, 后续只读不写;</li>
 *   <li>装配逻辑只此一处, 未来加新来源(例如租户定制 / 远程拉取)只需在本类追加步骤,
 *       调用方无感;</li>
 *   <li>classpath 目录路径("skills")集中在一处, 想换目录或加多个根目录也好维护。</li>
 * </ul>
 */
@Getter
@Slf4j
@Component
public class SkillsFactory {

    /** classpath 根目录, 装载 src/main/resources/skills/ 下的所有 Skill */
    private static final String CLASSPATH_SKILLS_DIR = "skills";

    /** 装配好的 Skills 实例, 启动后只读
     * -- GETTER --
     *  获取已装配好的
     *  实例。
     *  <p>
     *  注意: 调用方拿到的是同一份不可变对象, 不要尝试修改其内部状态。
     */
    private Skills skills;

    /**
     * 启动期装配 Skills。该方法只跑一次, 装配过程纯本地(读 classpath + 内存构造),
     * 不会发起任何网络请求, 启动开销可忽略。
     */
    @PostConstruct
    void init() {
        this.skills = assemble();
    }

    /**
     * 装配: classpath Skill + 自定义 Skill + 统一工具配置 → {@link Skills}。
     */
    private Skills assemble() {
        // 1. classpath 加载
        List<Skill> classpathSkills = ClassPathSkillLoader.loadSkills(CLASSPATH_SKILLS_DIR);
        // 2. 自定义 Skill
        List<Skill> customSkills = CustomSkillsProvider.customSkills();
        // 3. 合并(顺序: classpath 在前, 自定义在后; 部分模型对顺序敏感, 稳定顺序便于排查)
        List<Skill> all = new ArrayList<>(classpathSkills.size() + customSkills.size());
        all.addAll(classpathSkills);
        all.addAll(customSkills);

        // 4. 装配 + 应用工具级配置
        Skills assembled = Skills.builder()
                .skills(all)
                .activateSkillToolConfig(CustomSkillsProvider.activateSkillToolConfig())
                .readResourceToolConfig(CustomSkillsProvider.readResourceToolConfig())
                .build();

        log.info("[Skills 装配] classpath({}) = {}, 自定义({}) = {}, 总计 {} = {}",
                classpathSkills.size(),
                classpathSkills.stream().map(Skill::name).toList(),
                customSkills.size(),
                customSkills.stream().map(Skill::name).toList(),
                all.size(),
                all.stream().map(Skill::name).toList());
        return assembled;
    }
}
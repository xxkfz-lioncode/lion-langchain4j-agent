package com.lion.agent.classification;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 方式一: 基于 LLM 的情感分类(AI Services 注解式, 由 spring-boot-starter 自动生成代理 Bean)。
 * <p>
 * 工作原理:
 * <ul>
 *   <li>返回类型直接写 {@link Sentiment} 枚举, 框架会把枚举常量注入 prompt, 强制模型输出其中之一
 *       (LangChain4j 自动做结构化映射);</li>
 *   <li>返回 boolean 时同理, 框架约束模型只输出 true / false;</li>
 *   <li>单参数方法用 {@code {{it}}} 占位符引用入参。</li>
 * </ul>
 * <p>
 * 适用场景: 标签边界模糊(例如区分"投诉"还是"咨询"), 需要细粒度的自然语言推理。
 * 代价: 每句文本都走一次模型调用, 成本与延迟均较高。
 * <p>
 * 与项目既有 {@link com.lion.agent.assistant.AnnotatedAssistant} 的区别: 那个是对话式助手(返回
 * String), 本类是<b>结构化分类器</b>(返回枚举/布尔), 接口形态一致但语义不同。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel"   // 复用 application.yml 里配的千问 DashScope 模型
)
public interface SentimentAnalyzer {

    /**
     * 三分类: POSITIVE / NEUTRAL / NEGATIVE。
     * <p>
     * 注意: 枚举常量名会作为 prompt 中的候选标签传给模型, 因此保持英文大写常量名最稳。
     * 若模型偶发输出非枚举值(如小写 "positive"), 框架会抛 IllegalArgumentException,
     * 此时可改用 {@link #classifyRaw(String)} 接 String 后自己映射。
     */
    @SystemMessage("你是情感分类器, 只能输出 POSITIVE / NEUTRAL / NEGATIVE 之一, 不要任何解释、标点或多余字符。")
    @UserMessage("判断以下文本的情感: {{it}}")
    Sentiment classify(String text);

    /**
     * 二分类: 是否正面情绪(返回 boolean)。
     * <p>
     * 演示返回类型的多样性 — boolean 同样受框架结构化约束, 模型只会输出 true / false。
     */
    @SystemMessage("只回答 true 或 false, 不要任何解释。")
    @UserMessage("这句话表达的是正面情绪吗? 文本: {{it}}")
    boolean isPositive(String text);

    /**
     * 兜底版: 用 String 接原始输出, 自己 switch 映射到枚举。
     * <p>
     * 适合对稳定性要求高的场景(例如生产批量打标), 即使模型偶发输出大小写或近义词也能优雅兜住。
     */
    @SystemMessage("你是情感分类器, 只能输出以下三个词之一, 不要任何解释: 正面 / 中性 / 负面")
    @UserMessage("判断以下文本的情感(正面/中性/负面): {{it}}")
    String classifyRaw(String text);

    /** 把 {@link #classifyRaw(String)} 的中文输出映射回枚举 */
    default Sentiment mapRawToSentiment(String raw) {
        if (raw == null) {
            return Sentiment.NEUTRAL;
        }
        String s = raw.trim();
        if (s.startsWith("正") || s.equalsIgnoreCase("positive")) {
            return Sentiment.POSITIVE;
        }
        if (s.startsWith("负") || s.equalsIgnoreCase("negative")) {
            return Sentiment.NEGATIVE;
        }
        return Sentiment.NEUTRAL;
    }
}
package com.lion.agent.classification;

import dev.langchain4j.classification.ClassificationResult;
import dev.langchain4j.classification.EmbeddingModelTextClassifier;
import dev.langchain4j.classification.TextClassifier;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 方式二: 基于嵌入向量相似度的情感分类器。
 * <p>
 * 工作原理:
 * <ol>
 *   <li>启动时把每个标签对应的若干<b>典型例句</b>(见 {@link #EXAMPLES})用 EmbeddingModel 转成向量;</li>
 *   <li>分类时把待分类文本也转成向量, 与每个标签下的示例向量算相似度;</li>
 *   <li>按"均值/最大分加权"得到每个标签的总分, 过滤掉低于 {@code minScore} 的, 按 {@code maxResults} 截断后返回。</li>
 * </ol>
 * <p>
 * 适用场景: 标签清晰、能写出典型例句, 且<b>高频调用</b>(批量打标、实时审核)。
 * 优势: 不再每次都走 LLM, 延迟与成本都低一个量级。
 * <p>
 * 依赖: 注入的 {@link EmbeddingModel} 是 spring-boot-starter 自动装配的
 * {@code openAiEmbeddingModel}(DashScope text-embedding-v3, 见 application.yml),
 * 因此分类时仍会发起一次远端 embedding 调用; 如需彻底离线, 可换成
 * {@code AllMiniLmL6V2EmbeddingModel}(pom 里已加 langchain4j-embeddings-all-minilm-l6-v2)。
 * <p>
 * 调优参数(用第二个全参构造器时生效):
 * <ul>
 *   <li>{@code maxResults}: 每次最多返回几个标签(默认 1)</li>
 *   <li>{@code minScore}: 相似度低于该值的标签会被丢弃(默认 0)</li>
 *   <li>{@code meanToMaxScoreRatio}: 0=只用均值, 1=只用最大值, 0.5=两者各半(默认 0.5)</li>
 * </ul>
 */
@Slf4j
@Component
public class SentimentEmbeddingClassifier {

    /**
     * 每个分类标签的代表性例句 —— 示例越多越准, 实际项目可用 LLM 批量生成。
     * <p>
     * 用 {@link Map#of(Object, Object)} 即可, 框架接受 {@code Map<L, ? extends Collection<String>>}。
     */
    private static final Map<Sentiment, List<String>> EXAMPLES = Map.of(
            Sentiment.POSITIVE, List.of(
                    "质量很好, 非常满意",
                    "物流快, 推荐购买",
                    "客服态度好, 点赞",
                    "超出预期, 物超所值",
                    "包装精美, 用着舒心"
            ),
            Sentiment.NEUTRAL, List.of(
                    "还行吧, 一般般",
                    "没什么特别的",
                    "用了几天, 凑合",
                    "中规中矩, 没毛病",
                    "价格差不多, 可以接受"
            ),
            Sentiment.NEGATIVE, List.of(
                    "质量太差了, 很失望",
                    "发货慢, 不会再来",
                    "客服态度恶劣, 差评",
                    "和描述完全不符, 退货",
                    "做工粗糙, 缝隙明显"
            )
    );

    private final TextClassifier<Sentiment> classifier;

    public SentimentEmbeddingClassifier(EmbeddingModel embeddingModel) {
        // 默认构造器: maxResults=1, minScore=0, meanToMaxScoreRatio=0.5
        this.classifier = new EmbeddingModelTextClassifier<>(embeddingModel, EXAMPLES);
        // 如要更细粒度控制, 用全参构造器:
        // new EmbeddingModelTextClassifier<>(embeddingModel, EXAMPLES, 3, 0.6, 0.5);
        log.info("[情感分类-Embedding] 初始化完成, 各标签示例数: POSITIVE={}, NEUTRAL={}, NEGATIVE={}",
                EXAMPLES.get(Sentiment.POSITIVE).size(),
                EXAMPLES.get(Sentiment.NEUTRAL).size(),
                EXAMPLES.get(Sentiment.NEGATIVE).size());
    }

    /**
     * 分类: 返回最可能的若干标签(按相似度排序)。
     * <p>
     * 可能返回 0 个、1 个或多个标签 —— 取决于 {@code maxResults} / {@code minScore} 阈值。
     */
    public List<Sentiment> classify(String text) {
        return classifier.classify(text);
    }

    /**
     * 带分数的分类: 用于排查"为什么判错"时定位相似度差异。
     * <p>
     * 输出形如: {@code { POSITIVE: 0.82, NEUTRAL: 0.41, NEGATIVE: 0.27 }}
     */
    public ClassificationResult<Sentiment> classifyWithScores(String text) {
        return classifier.classifyWithScores(text);
    }
}
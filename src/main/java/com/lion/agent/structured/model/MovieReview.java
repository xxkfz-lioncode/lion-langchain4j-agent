package com.lion.agent.structured.model;

import com.lion.agent.classification.Sentiment;
import com.lion.agent.structured.StructuredTarget;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import lombok.Data;

import java.util.Arrays;

/**
 * 演示用目标类型: 把一段影评抽成结构化对象。
 * <p>
 * 选用 Lombok {@code @Data} 而不是 record, 原因:
 * <ol>
 *   <li>与项目其它 DTO 风格一致({@code pojo/dto} 包大量使用 {@code @Data});</li>
 *   <li>{@link cn.hutool.json.JSONUtil#toBean(String, Class)} 在 POJO 上更稳 —
 *       record 是 final 字段, Hutool 5.8.x 在 JDK 17 下依赖反射写 final 字段,
 *       不同模块开放程度上表现不一致, POJO + setter 走的是主流路径。</li>
 * </ol>
 * <p>
 * 字段名同时决定了三种方式的输出契约:
 * <ul>
 *   <li>方式一: JSON Schema 里的属性名 + 类型, 由模型侧保证;</li>
 *   <li>方式二: 字段说明拼到提示词里, 模型用 json_object 模式输出;</li>
 *   <li>方式三: 全靠提示词描述字段。</li>
 * </ul>
 */
@Data
public class MovieReview {

    private String movieName;
    private Sentiment sentiment;
    private int rating;
    private boolean recommended;
    private String reviewer;

    /** Jackson 与 Hutool 反序列化都需要无参构造, Lombok @Data 不会自动生成, 这里显式声明 */
    public MovieReview() {
    }

    /** 给单元测试 / 直接构造用的便捷构造器 */
    public MovieReview(String movieName, Sentiment sentiment, int rating, boolean recommended, String reviewer) {
        this.movieName = movieName;
        this.sentiment = sentiment;
        this.rating = rating;
        this.recommended = recommended;
        this.reviewer = reviewer;
    }

    /** 字段说明(方式二/三 没有 schema, 只能靠提示词描述字段, 所以单独维护一份) */
    public static final String FIELD_GUIDE = """
            movieName  : 电影名称(字符串, 原文没提到就填 "未知")
            sentiment  : 情感倾向(字符串, 只能是 POSITIVE / NEUTRAL / NEGATIVE 之一)
            rating     : 评分(整数, 取值 1~5)
            recommended: 是否推荐观看(布尔, true / false)
            reviewer   : 评论者昵称(字符串, 原文没提到就填 "匿名")
            """;

    /** 演示目标: 类型 + JSON Schema + 字段说明, 三种方式共用同一份描述 */
    public static StructuredTarget<MovieReview> target() {
        return StructuredTarget.of(MovieReview.class, jsonSchema(), FIELD_GUIDE);
    }

    /** 方式一用的 JSON Schema: 字段名/类型/枚举值/必填项都在这里声明 */
    public static JsonSchema jsonSchema() {
        return JsonSchema.builder()
                .name("MovieReview")
                .rootElement(JsonObjectSchema.builder()
                        .addStringProperty("movieName", "电影名称, 原文没提到填 未知")
                        .addEnumProperty("sentiment", Arrays.stream(Sentiment.values()).map(Enum::name).toList())
                        .addIntegerProperty("rating", "1~5 的整数评分")
                        .addBooleanProperty("recommended", "是否推荐观看")
                        .addStringProperty("reviewer", "评论者昵称, 原文没提到填 匿名")
                        .required("movieName", "sentiment", "rating", "recommended", "reviewer")
                        .build())
                .build();
    }
}
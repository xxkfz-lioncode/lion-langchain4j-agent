package com.lion.agent.structured.parser;

import cn.hutool.json.JSONUtil;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.structured.StructuredOutputService;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型输出 -> Java 对象的解析工具(方式层三种方式共用)。
 * <p>
 * 实际项目里模型输出很少是"干净的 JSON", 这里集中处理三类脏数据, 避免每个实现类各写一遍:
 * <ol>
 *   <li>被 markdown 围栏包住: {@code ```json { ... } ```};</li>
 *   <li>前后带解释文字: "好的, 抽取结果如下: { ... } 希望有帮助";</li>
 *   <li>多出 schema 之外的字段(模型自由发挥), {@link JSONUtil#toBean(String, Class)}
 *       默认就忽略 bean 中不存在的字段, 等价于 Jackson 的 FAIL_ON_UNKNOWN_PROPERTIES=false。</li>
 * </ol>
 * <p>
 * 依赖选择: 与项目其它模块保持一致, 用 {@link cn.hutool.json.JSONUtil}(WeatherController 已在用),
 * 不在工具类层面再额外引入 Jackson; Jackson 仍由 Spring MVC 默认转换器使用, 不受影响。
 * <p>
 * 注意: 预处理只能提高成功率, 不能保证 100% 解析成功 —— 方式二/三 的结构约束本来就弱,
 * 真正的兜底手段是 {@link StructuredOutputService#extractWithFallback} 的降级/重试。
 */
@Slf4j
public final class JsonOutputParser {

    private JsonOutputParser() {
    }

    /**
     * 把模型输出的文本解析为目标类型。
     *
     * @param raw  模型原始输出
     * @param type 目标类型
     * @return 解析后的对象
     */
    public static <T> T parse(String raw, Class<T> type) {
        String json = extractJsonObject(raw);
        try {
            return JSONUtil.toBean(json, type);
        } catch (RuntimeException e) {
            // Hutool 在不同失败路径上会抛 JSONException / ConvertException, 都属 RuntimeException
            log.error("[结构化输出] 解析为 {} 失败, 清洗后的 JSON: {}", type.getSimpleName(), json);
            throw new BusinessException("模型输出无法映射为 " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 清洗模型输出, 截出最外层的 JSON 对象。
     * 步骤: 去 markdown 围栏 -> 取第一个 '{' 到最后一个 '}' 之间的内容。
     */
    private static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("模型输出为空, 无法解析");
        }
        String text = raw.trim();

        // 1. 去掉 ```json ... ``` / ``` ... ``` 围栏
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }

        // 2. 截取第一个 '{' 到最后一个 '}'(排除前置说明文字与后置的补充说明)
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }
}
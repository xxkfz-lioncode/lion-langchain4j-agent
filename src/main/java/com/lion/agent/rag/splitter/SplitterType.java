package com.lion.agent.rag.splitter;

import lombok.Getter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档切分方式(策略枚举): 一种方式 = 一个 langchain4j {@code DocumentSplitter} 实现 + 一套默认参数。
 * <p>
 * 每种方式都由 {@link DocumentSplitterFactory} 按请求参数构建实例, 前端下拉框数据源也由本枚举生成
 * (见 {@code GET /api/rag/splitters}), 因此前后端只需维护这一份清单。
 * <p>
 * 新增一种切分方式(三步):
 * <ol>
 *     <li>本枚举追加一项(展示名/短名/默认块大小/是否支持自定义正则);</li>
 *     <li>{@link DocumentSplitterFactory#build} 的 switch 中补一个分支;</li>
 *     <li>如需持久化新参数, 在 docs/sql/init.sql 的 rag_document 中补列。</li>
 * </ol>
 */
@Getter
public enum SplitterType {

    /** 递归切分: 段落 → 句子 → 词 逐级下钻(langchain4j 官方默认, 不确定选它) */
    RECURSIVE("recursive", "递归切分(推荐)", "递归",
            "按 段落 → 句子 → 词 逐级下钻, 兼顾语义与长度, 通用性最好",
            300, 50, false, null, null),

    /** 按段落切分: 空行为界, 段落语义最完整 */
    PARAGRAPH("paragraph", "按段落切分", "段落",
            "以空行为界切开, 段落语义完整; 单段超长时自动继续下钻",
            300, 50, false, null, null),

    /** 按句子切分: 中英文句末标点断句, 片段粒度最细(适合 FAQ) */
    SENTENCE("sentence", "按句子切分", "句子",
            "以句号/问号/叹号断句, 适合问答型、条款型资料",
            300, 50, false, null, null),

    /** 按行切分: 换行为界(适合日志/代码/逐行清单) */
    LINE("line", "按行切分", "按行",
            "以换行为界, 适合日志、代码、逐行清单型文档",
            300, 50, false, null, null),

    /** 按词切分: 空格分词(英文资料) */
    WORD("word", "按词切分", "按词",
            "以空格分词, 适合英文资料(中文建议用递归/段落/句子)",
            300, 50, false, null, null),

    /** 按字符切分: 定长硬切, 仅作兜底 */
    CHARACTER("character", "按字符切分", "字符",
            "按固定字符数硬切, 可能截断词句, 仅作兜底",
            300, 50, false, null, null),

    /** 按正则切分: 自定义分节规则, 默认按 Markdown 标题 */
    REGEX("regex", "按正则切分", "正则",
            "按自定义正则分节, 默认按 Markdown 标题(# / ##)",
            300, 50, true,
            "(?m)(?=^#{1,6}\\s)",
            "推荐零宽断言(?=...), 不会吃掉分隔内容; 例: (?m)(?=^#{1,6}\\s) 按 Markdown 标题");

    /** code → 枚举(含少量别名) */
    private static final Map<String, SplitterType> INDEX_BY_CODE = new HashMap<>();

    static {
        for (SplitterType type : values()) {
            INDEX_BY_CODE.put(type.code, type);
        }
        // 常见别名, 兼容前端旧参数 / 第三方调用
        INDEX_BY_CODE.put("md", REGEX);
        INDEX_BY_CODE.put("markdown", REGEX);
        INDEX_BY_CODE.put("char", CHARACTER);
        INDEX_BY_CODE.put("para", PARAGRAPH);
    }

    /** 接口传参用的编码(小写) */
    private final String code;
    /** 展示名(下拉框) */
    private final String label;
    /** 短名(文件列表标签) */
    private final String shortLabel;
    /** 说明文案 */
    private final String description;
    /** 默认块大小(字符) */
    private final int defaultSegmentSize;
    /** 默认重叠(字符) */
    private final int defaultOverlap;
    /** 是否支持自定义正则(仅 REGEX 为 true) */
    private final boolean patternSupported;
    /** 默认正则(patternSupported=true 时作为输入框占位) */
    private final String defaultPattern;
    /** 正则填写提示 */
    private final String patternHint;

    SplitterType(String code, String label, String shortLabel, String description,
                 int defaultSegmentSize, int defaultOverlap, boolean patternSupported,
                 String defaultPattern, String patternHint) {
        this.code = code;
        this.label = label;
        this.shortLabel = shortLabel;
        this.description = description;
        this.defaultSegmentSize = defaultSegmentSize;
        this.defaultOverlap = defaultOverlap;
        this.patternSupported = patternSupported;
        this.defaultPattern = defaultPattern;
        this.patternHint = patternHint;
    }

    /** 未指定切分方式时使用的默认策略 */
    public static SplitterType defaultType() {
        return RECURSIVE;
    }

    /**
     * 按编码查切分方式(忽略大小写, 兼容少量别名)
     *
     * @param code 接口传入的编码, 可为空
     * @return 未传返回默认方式; 传了未知编码返回 null(由调用方组织报错信息)
     */
    public static SplitterType of(String code) {
        if (code == null || code.isBlank()) {
            return defaultType();
        }
        return INDEX_BY_CODE.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部可选编码(错误提示用) */
    public static String supportedCodes() {
        return Arrays.stream(values()).map(SplitterType::getCode).collect(Collectors.joining(" / "));
    }
}

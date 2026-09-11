package com.lion.agent.rag.splitter;

import com.lion.agent.common.exception.BusinessException;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文档切分器工厂: 把接口传参(可为空)解析为最终参数, 并构建对应的 langchain4j 切分器。
 * <p>
 * 设计意图: 切分方式只暴露一个 code({@link SplitterType}), 参数统一走
 * {@link SplitterConfig}(块大小/重叠/正则, 均可空), 新增方式只需在
 * {@link #build} 的 switch 中加分支, 上层(RagService / Controller / 前端)无需改动。
 */
@Component
public class DocumentSplitterFactory {

    /** 块大小下限(字符) */
    public static final int MIN_SEGMENT_SIZE = 50;
    /** 块大小上限(字符, 约等于一次 embedding 的合理长度) */
    public static final int MAX_SEGMENT_SIZE = 4000;
    /** 正则切分: 分节后拼接用的分隔串(零宽断言不消耗内容, 故为空串) */
    private static final String REGEX_JOIN_DELIMITER = "";

    /**
     * 解析参数 + 校验 + 构建切分器(参数为空取该方式默认值; 非法直接抛业务异常, 便于前端提示)
     */
    public ResolvedSplitter resolve(SplitterConfig config) {
        SplitterConfig raw = config == null ? SplitterConfig.defaults() : config;

        SplitterType type = SplitterType.of(raw.type());
        if (type == null) {
            throw new BusinessException("不支持的切分方式: " + raw.type()
                    + ", 可选值: " + SplitterType.supportedCodes());
        }
        int segmentSize = resolveSegmentSize(type, raw.segmentSize());
        int overlap = resolveOverlap(type, raw.overlap(), segmentSize);
        String pattern = resolvePattern(type, raw.pattern());
        return new ResolvedSplitter(type, segmentSize, overlap, pattern,
                build(type, segmentSize, overlap, pattern));
    }

    /** 块大小: 空/非正数取默认值, 超范围报错 */
    private int resolveSegmentSize(SplitterType type, Integer segmentSize) {
        int size = (segmentSize == null || segmentSize <= 0) ? type.getDefaultSegmentSize() : segmentSize;
        if (size < MIN_SEGMENT_SIZE || size > MAX_SEGMENT_SIZE) {
            throw new BusinessException("切分块大小需在 " + MIN_SEGMENT_SIZE + " ~ " + MAX_SEGMENT_SIZE + " 字符之间");
        }
        return size;
    }

    /** 重叠: 空/负数取默认值, 必须小于块大小(langchain4j 硬性要求) */
    private int resolveOverlap(SplitterType type, Integer overlap, int segmentSize) {
        int value = (overlap == null || overlap < 0) ? type.getDefaultOverlap() : overlap;
        if (value >= segmentSize) {
            throw new BusinessException("重叠字符数需小于切分块大小(当前 " + value + " >= " + segmentSize + ")");
        }
        return value;
    }

    /** 正则: 仅 regex 方式使用; 空取默认正则, 语法非法直接报错 */
    private String resolvePattern(SplitterType type, String pattern) {
        if (!type.isPatternSupported()) {
            return null;
        }
        String regex = (pattern == null || pattern.isBlank()) ? type.getDefaultPattern() : pattern.trim();
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new BusinessException("切分正则非法: " + e.getDescription());
        }
        return regex;
    }

    /**
     * 按最终参数构建切分器(新增切分方式的唯一改动点)
     */
    private DocumentSplitter build(SplitterType type, int segmentSize, int overlap, String pattern) {
        return switch (type) {
            case RECURSIVE -> DocumentSplitters.recursive(segmentSize, overlap);
            case PARAGRAPH -> new DocumentByParagraphSplitter(segmentSize, overlap);
            case SENTENCE -> new DocumentBySentenceSplitter(segmentSize, overlap);
            case LINE -> new DocumentByLineSplitter(segmentSize, overlap);
            case WORD -> new DocumentByWordSplitter(segmentSize, overlap);
            case CHARACTER -> new DocumentByCharacterSplitter(segmentSize, overlap);
            // 正则: 按自定义正则分节; 片段仍超长时由子切分器(默认递归)继续下钻
            case REGEX -> new DocumentByRegexSplitter(pattern, REGEX_JOIN_DELIMITER, segmentSize, overlap);
        };
    }
}

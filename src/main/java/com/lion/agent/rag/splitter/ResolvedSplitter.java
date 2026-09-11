package com.lion.agent.rag.splitter;

import dev.langchain4j.data.document.DocumentSplitter;

/**
 * 已解析校验后的切分方案: 最终参数 + 可直接使用的切分器实例
 * <p>
 * 由 {@link DocumentSplitterFactory#resolve} 产出, 供业务侧"落库记录参数 + 执行切分"一次拿到。
 *
 * @param type        切分方式(已兜底为默认值)
 * @param segmentSize 最终块大小(字符)
 * @param overlap     最终重叠字符数
 * @param pattern     最终正则(非 regex 方式为 null)
 * @param splitter    langchain4j 切分器实例
 */
public record ResolvedSplitter(SplitterType type, int segmentSize, int overlap, String pattern,
                               DocumentSplitter splitter) {

    /** 摘要文案, 如 "递归切分(推荐)(300/50)" */
    public String display() {
        return type.getLabel() + "(" + segmentSize + "/" + overlap + ")";
    }
}

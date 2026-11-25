package com.lion.agent.ai.rag.parse;

import java.util.List;

/**
 * 文档元素解析器 SPI: 把一种格式的原始字节流解析成有序元素流。
 * 实现按格式注册在 {@link ElementParserRouter}(fileType → parser), 解析失败抛异常由 Worker 统一处理。
 */
public interface ElementParser {

    /**
     * 解析原始文件 → 有序元素列表(保持文档出现顺序)。
     * 图片等提取产物由实现落盘存储, 元素携带对象Key(ParseContext 提供 docId/fileName 上下文)。
     */
    List<DocElement> parse(ParseContext ctx) throws Exception;
}

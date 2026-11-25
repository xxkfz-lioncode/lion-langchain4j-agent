package com.lion.agent.ai.rag.parse;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本(.txt)解析器: 整个文件一个段落元素(无页码/无结构)。
 */
@Component
public class TextElementParser implements ElementParser {

    @Override
    public List<DocElement> parse(ParseContext ctx) {
        String text = new String(ctx.getData(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new IllegalStateException("文件内容为空");
        }
        return List.of(DocElement.paragraph(text, null));
    }
}

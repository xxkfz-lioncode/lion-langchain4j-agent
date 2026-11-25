package com.lion.agent.ai.rag.parse;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元素解析器路由: 按 fileType 把原始字节流交给对应格式解析器。
 * 与 {@link com.lion.agent.ai.rag.util.RagUtils#typeOf(String)} 的类型编码一一对应。
 */
@Component
public class ElementParserRouter {

    private final Map<String, ElementParser> parsers;

    public ElementParserRouter(MarkdownElementParser mdParser,
                               DocxElementParser docxParser,
                               XlsxElementParser xlsxParser,
                               PdfElementParser pdfParser,
                               TextElementParser textParser) {
        this.parsers = Map.of(
                "md", mdParser,
                "docx", docxParser,
                "xlsx", xlsxParser,
                "xls", xlsxParser,
                "pdf", pdfParser,
                "txt", textParser);
    }

    /** 按 ctx.fileType 解析(未知类型明确报错, 不静默兜底) */
    public List<DocElement> parse(ParseContext ctx) throws Exception {
        ElementParser parser = parsers.get(ctx.getFileType());
        if (parser == null) {
            throw new IllegalStateException("暂不支持解析的文档类型: " + ctx.getFileType());
        }
        return parser.parse(ctx);
    }
}

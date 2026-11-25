package com.lion.agent.ai.rag.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word(.docx) 解析器: POI XWPF 按 body 顺序解析, 产出 标题/段落/表格 元素流(保持文档排版顺序)。
 * <p>
 * 识别规则:
 * - 标题: 段落样式 styleId 匹配 Heading1~6(英文 Word)或 "1"~"6"(中文 Word 常见 styleId);
 * - 表格: XWPFTable → Markdown 表格原文(单元格内换行折叠为空格, | 转义);
 * - 文本框/艺术字/图片等非 body 内联内容不做处理(一期范围)。
 */
@Component
public class DocxElementParser implements ElementParser {

    /** Heading1~6(兼容大小写与空格变体) */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-6])");
    /** 中文 Word 的标题 styleId 是纯数字 "1"~"6" */
    private static final Pattern NUMERIC_STYLE = Pattern.compile("[1-6]");

    @Override
    public List<DocElement> parse(ParseContext ctx) throws Exception {
        List<DocElement> elements = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(ctx.getData()))) {
            for (IBodyElement body : doc.getBodyElements()) {
                if (body instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    int level = headingLevel(p);
                    if (level > 0) {
                        elements.add(DocElement.title(text.strip(), level, null));
                    } else {
                        elements.add(DocElement.paragraph(text.strip(), null));
                    }
                } else if (body instanceof XWPFTable table) {
                    String md = toMarkdown(table);
                    if (md != null) {
                        elements.add(DocElement.table(md, null));
                    }
                }
            }
        }
        if (elements.isEmpty()) {
            throw new IllegalStateException("Word 文档没有可提取的文本内容");
        }
        return elements;
    }

    /** 段落样式 → 标题层级(0 = 普通段落) */
    private int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null || style.isBlank()) {
            return 0;
        }
        style = style.trim();
        Matcher m = HEADING_STYLE.matcher(style);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        if (NUMERIC_STYLE.matcher(style).matches()) {
            return Integer.parseInt(style);
        }
        return 0;
    }

    /** 表格 → Markdown 原文; 列数按最大列数对齐(短行补空单元格), 分隔行插在首行后 */
    private String toMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return null;
        }
        int cols = rows.stream().mapToInt(r -> r.getTableCells().size()).max().orElse(0);
        if (cols == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String cell = c < cells.size() ? cellText(cells.get(c)) : "";
                sb.append(' ').append(cell).append(' ').append('|');
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                sb.append(" --- |".repeat(cols));
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** 单元格文本: 换行折叠为空格, 竖线转义(否则破坏 Markdown 表格结构) */
    private String cellText(XWPFTableCell cell) {
        String text = cell.getText();
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ")
                .replace("|", "\\|").strip();
    }
}

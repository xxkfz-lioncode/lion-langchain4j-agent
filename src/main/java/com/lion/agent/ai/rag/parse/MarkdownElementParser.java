package com.lion.agent.ai.rag.parse;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器: 逐行状态机, 把 .md 文件解析成 标题/段落/表格/列表 元素流。
 * <p>
 * 识别规则:
 * - ATX 标题: #{1,6} 文本 → TITLE(层级 = # 个数);
 * - 表格: 连续的 |...| 行(含 |---| 分隔行)聚合成一个 TABLE 元素, 保留 Markdown 原文;
 * - 列表: 连续的 -、*、N. 条目聚合为 LIST;
 * - 代码块: ``` 围栏整体保留为 PARAGRAPH;
 * - 其余非空行累积为 PARAGRAPH, 空行结束段落。
 * <p>
 * 图片行(![alt](url))不做特殊处理 —— 一期图片不入库, 链接语法随段落文本自然保留。
 */
@Component
public class MarkdownElementParser implements ElementParser {

    /** ATX 标题: #~###### + 空格 + 文本 */
    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.+)");
    /** 无序列表(短横线/星号/加号)或有序列表(N.) */
    private static final Pattern LIST_ITEM = Pattern.compile("([-*+]|\\d+\\.)\\s+.+");

    @Override
    public List<DocElement> parse(ParseContext ctx) {
        String content = new String(ctx.getData(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new IllegalStateException("文件内容为空");
        }

        List<DocElement> elements = new ArrayList<>();
        StringBuilder para = new StringBuilder();      // 段落累积
        List<String> tableLines = new ArrayList<>();   // 表格行收集
        StringBuilder listBuf = new StringBuilder();   // 列表条目累积
        StringBuilder codeBuf = null;                  // 代码块累积(null = 不在代码块内)
        boolean tableOpen = false;

        for (String raw : content.split("\r?\n")) {
            String line = raw.stripTrailing();
            String t = line.trim();

            // ---- 代码块围栏 ----
            if (t.startsWith("```")) {
                flushAll(elements, para, listBuf, tableLines);
                tableOpen = false;
                if (codeBuf == null) {
                    codeBuf = new StringBuilder(line).append('\n');
                } else {
                    codeBuf.append(line).append('\n');
                    elements.add(DocElement.paragraph(codeBuf.toString().strip(), null));
                    codeBuf = null;
                }
                continue;
            }
            if (codeBuf != null) {
                codeBuf.append(line).append('\n');
                continue;
            }

            // ---- 标题(同时结束进行中的段落/列表/表格) ----
            Matcher m = HEADING.matcher(t);
            if (m.matches()) {
                flushAll(elements, para, listBuf, tableLines);
                tableOpen = false;
                elements.add(DocElement.title(m.group(2).strip(), m.group(1).length(), null));
                continue;
            }

            // ---- 表格行(以 | 开头并以 | 结尾) ----
            if (t.startsWith("|") && t.endsWith("|") && t.length() > 1) {
                flushPara(elements, para);
                flushList(elements, listBuf);
                tableLines.add(t);
                tableOpen = true;
                continue;
            }
            if (tableOpen) {
                // 非表格行 → 表格结束
                flushTable(elements, tableLines);
                tableOpen = false;
            }

            // ---- 列表条目 ----
            if (LIST_ITEM.matcher(t).matches()) {
                flushPara(elements, para);
                listBuf.append(t).append('\n');
                continue;
            }
            flushList(elements, listBuf);

            // ---- 段落 / 空行 ----
            if (t.isEmpty()) {
                flushPara(elements, para);
            } else {
                para.append(line).append('\n');
            }
        }

        // 文件收尾
        if (codeBuf != null) {
            elements.add(DocElement.paragraph(codeBuf.toString().strip(), null));
        }
        flushAll(elements, para, listBuf, tableLines);
        if (tableOpen) {
            flushTable(elements, tableLines);
        }
        return elements;
    }

    private void flushAll(List<DocElement> elements, StringBuilder para, StringBuilder listBuf, List<String> tableLines) {
        flushPara(elements, para);
        flushList(elements, listBuf);
        flushTable(elements, tableLines);
    }

    private void flushPara(List<DocElement> elements, StringBuilder para) {
        if (para.length() > 0) {
            String text = para.toString().strip();
            if (!text.isEmpty()) {
                elements.add(DocElement.paragraph(text, null));
            }
            para.setLength(0);
        }
    }

    private void flushList(List<DocElement> elements, StringBuilder listBuf) {
        if (listBuf.length() > 0) {
            String text = listBuf.toString().strip();
            if (!text.isEmpty()) {
                elements.add(DocElement.list(text, null));
            }
            listBuf.setLength(0);
        }
    }

    /** 表格行 → Markdown 原文(TABLE 元素, 结构分块器保证其原子性); 孤立 | 行按段落处理 */
    private void flushTable(List<DocElement> elements, List<String> tableLines) {
        if (tableLines.size() >= 2) {
            elements.add(DocElement.table(String.join("\n", tableLines), null));
        } else if (tableLines.size() == 1) {
            elements.add(DocElement.paragraph(tableLines.get(0), null));
        }
        tableLines.clear();
    }
}

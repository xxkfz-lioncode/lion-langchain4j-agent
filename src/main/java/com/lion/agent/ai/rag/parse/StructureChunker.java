package com.lion.agent.ai.rag.parse;

import com.lion.agent.ai.rag.service.impl.RagServiceImpl;
import com.lion.agent.ai.rag.splitter.DocumentSplitterFactory;
import com.lion.agent.ai.rag.splitter.SplitterConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 结构感知分块器: 元素流 → 可入库片段。这是"复杂文档召回质量"的关键一环。
 * <p>
 * 与传统"机械数 300 字切一刀"的区别:
 * 1. 标题 → 维护章节路径栈, 每个片段知道自己在文档哪个章节(section 元数据, 引用展示/过滤都可用);
 * 2. 表格 → 整表原子成块, 绝不与正文混合切分(300 字硬切会把表格切碎, 检索即失效);
 *    超过 {@link #MAX_TABLE_CHARS} 才按行分块, 且每块重复表头(行脱离表头就失去列语义);
 * 3. 段落/列表 → 聚合到当前章节, 超过块大小按<b>用户所选切分方式</b>(递归/段落/句子/按行/词/字符/正则)
 *    二次切分(复用 {@link DocumentSplitterFactory} 构建的 langchain4j 切分器, 与解析弹窗语义一致);
 * 4. PDF 段落携带页码, 段落跨页时先收口再开新块(页码不混淆)。
 * <p>
 * 输出片段的元数据: elementType / section / page, 由 RagParseWorker 追加 docId/fileName 后入库
 * (Milvus metadata JSON + rag_segment 表两处同步)。
 */
@Component
public class StructureChunker {

    /** 表格原子块字符上限: 在此以内整表一块(允许远大于段落块, 表格完整性优先); 超过才按行分块 */
    private static final int MAX_TABLE_CHARS = 2000;

    /** 默认块大小/重叠(rag_document 上未持久化时的兜底, 与历史默认一致) */
    private static final int DEFAULT_CHUNK_SIZE = 300;
    private static final int DEFAULT_OVERLAP = 50;

    /** langchain4j 切分器工厂: 按解析弹窗所选方式构建段落二次切分器 */
    private final DocumentSplitterFactory splitterFactory;

    public StructureChunker(DocumentSplitterFactory splitterFactory) {
        this.splitterFactory = splitterFactory;
    }

    /**
     * 元素流 → 片段列表。
     *
     * @param elements     解析产出的有序元素
     * @param chunkSize    段落块大小(字符; 上传/解析配置, 兜底 300)
     * @param overlap      段落切分重叠(字符, 兜底 50)
     * @param splitterType 切分方式编码(recursive/paragraph/sentence/line/word/character/regex;
     *                     空/未知回退递归; 只作用于段落文本, 表格/图片豁免)
     * @param pattern      正则切分方式的分节正则(仅 regex 方式使用, 空取该方式默认)
     */
    public List<TextSegment> chunk(List<DocElement> elements, Integer chunkSize, Integer overlap,
                                   String splitterType, String pattern) {
        int size = chunkSize == null || chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int over = overlap == null || overlap < 0 ? DEFAULT_OVERLAP : Math.min(overlap, size - 1);
        DocumentSplitter paraSplitter = resolveSplitter(splitterType, pattern, size, over);

        List<TextSegment> out = new ArrayList<>();
        Deque<Integer> levelStack = new ArrayDeque<>();   // 章节层级栈
        Deque<String> titleStack = new ArrayDeque<>();    // 章节标题栈(与 levelStack 同步)
        StringBuilder para = new StringBuilder();          // 段落聚合缓冲
        Integer paraPage = null;                           // 缓冲所属页(跨页即收口)

        for (DocElement el : elements) {
            switch (el.getType()) {
                case TITLE -> {
                    flushPara(out, para, paraPage, levelStack, titleStack, size, over, paraSplitter);
                    paraPage = null;
                    // 弹出所有 >= 当前层级的历史章节, 再压入当前标题(维护章节树路径)
                    while (!levelStack.isEmpty() && levelStack.peek() >= el.getTitleLevel()) {
                        levelStack.pop();
                        titleStack.pop();
                    }
                    levelStack.push(el.getTitleLevel());
                    titleStack.push(el.getText());
                }
                case TABLE -> {
                    flushPara(out, para, paraPage, levelStack, titleStack, size, over, paraSplitter);
                    paraPage = null;
                    chunkTable(out, el, sectionPath(levelStack, titleStack));
                }
                case PARAGRAPH, LIST, TEXT -> {
                    String text = el.getText();
                    if (text == null || text.isBlank()) {
                        continue; // 图片等占位元素跳过
                    }
                    // 跨页边界: 先收口旧段落, 并把页号重置为新页(否则下一页段落会错标上一页页码)
                    if (paraPage != null && el.getPage() != null && !paraPage.equals(el.getPage())) {
                        flushPara(out, para, paraPage, levelStack, titleStack, size, over, paraSplitter);
                        paraPage = null;
                    }
                    if (paraPage == null) {
                        paraPage = el.getPage();
                    }
                    if (!para.isEmpty() && para.length() + text.length() > size) {
                        flushPara(out, para, paraPage, levelStack, titleStack, size, over, paraSplitter);
                    }
                    para.append(text).append('\n');
                }
                // IMAGE: 提取产物已落 rag_image 留底, 不参与向量检索(看图说话落地前)
                case IMAGE -> { }
            }
        }
        flushPara(out, para, paraPage, levelStack, titleStack, size, over, paraSplitter);
        return out;
    }

    /** 按所选切分方式构建段落二次切分器; 方式空/未知/构建失败一律回退默认递归(不阻断解析) */
    private DocumentSplitter resolveSplitter(String splitterType, String pattern, int size, int over) {
        try {
            return splitterFactory.resolve(new SplitterConfig(splitterType, size, over, pattern)).splitter();
        } catch (Exception e) {
            return splitterFactory.resolve(new SplitterConfig(null, size, over, null)).splitter();
        }
    }

    /**
     * 段落收口: 聚合文本 ≤ 块大小整块出; 超长按所选切分方式二次切分
     * (切分器异常时回退字符窗口兜底, 保证解析不失败)。
     */
    private void flushPara(List<TextSegment> out, StringBuilder para, Integer page,
                           Deque<Integer> levelStack, Deque<String> titleStack,
                           int size, int over, DocumentSplitter paraSplitter) {
        if (para.isEmpty()) {
            return;
        }
        String text = para.toString().strip();
        para.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String path = sectionPath(levelStack, titleStack);
        for (String piece : splitPara(text, size, over, paraSplitter)) {
            out.add(segment(piece, ElementType.PARAGRAPH, page, path));
        }
    }

    /** 段落二次切分: 优先 langchain4j 切分器(按所选方式), 异常回退字符窗口 */
    private List<String> splitPara(String text, int size, int over, DocumentSplitter paraSplitter) {
        try {
            return paraSplitter.split(Document.from(text)).stream()
                    .map(TextSegment::text)
                    .filter(t -> t != null && !t.isBlank())
                    .toList();
        } catch (Exception e) {
            return splitByWindow(text, size, over);
        }
    }

    /**
     * 表格分块: 整表 ≤ MAX_TABLE_CHARS 原子出块; 超长按行分块且每块重复表头+分隔行。
     * 每个表格片段附带行号元数据(rowStart/rowEnd/rowTotal, 数据行从 1 起、不含表头),
     * 供片段抽屉展示"第 1~40 行 / 共 1000 行"。
     */
    private void chunkTable(List<TextSegment> out, DocElement el, String path) {
        String[] lines = el.getText().split("\n");
        Integer page = el.getPage();
        // 表头(第 0 行) + 分隔行(第 1 行, 形如 | --- |)作为每块的固定前缀
        boolean hasSep = lines.length > 1 && lines[1].replaceAll("[\\s\\-|]", "").isEmpty();
        String header = hasSep ? lines[0] + "\n" + lines[1] : lines[0];
        int dataStart = hasSep ? 2 : 1;
        // 数据行总数: 排除表头/分隔行, 以及解析层的截断提示行("> 注: ...")
        int rowTotal = 0;
        for (int i = dataStart; i < lines.length; i++) {
            if (!lines[i].startsWith("> 注:")) {
                rowTotal++;
            }
        }

        if (el.getText().length() <= MAX_TABLE_CHARS) {
            out.add(segment(el.getText(), ElementType.TABLE, page, path,
                    rowTotal > 0 ? 1 : null, rowTotal > 0 ? rowTotal : null, rowTotal));
            return;
        }

        StringBuilder cur = new StringBuilder(header);
        int blockStart = 1;   // 当前块起始数据行号
        int blockRows = 0;    // 当前块已装数据行数
        for (int i = dataStart; i < lines.length; i++) {
            String line = lines[i];
            boolean note = line.startsWith("> 注:");   // 截断提示: 留在文本里, 但不计入行号
            if (!note && cur.length() + line.length() + 1 > MAX_TABLE_CHARS && blockRows > 0) {
                out.add(segment(cur.toString().strip(), ElementType.TABLE, page, path,
                        blockStart, blockStart + blockRows - 1, rowTotal));
                cur.setLength(0);
                cur.append(header);
                blockStart += blockRows;
                blockRows = 0;
            }
            cur.append('\n').append(line);
            if (!note) {
                blockRows++;
            }
        }
        String rest = cur.toString().strip();
        if (rest.length() > header.length()) {
            out.add(segment(rest, ElementType.TABLE, page, path,
                    blockRows > 0 ? blockStart : null,
                    blockRows > 0 ? blockStart + blockRows - 1 : null, rowTotal));
        }
    }

    /** 章节路径: 标题栈自顶向下拼接("部署要求 > 2.1 服务器要求") */
    private String sectionPath(Deque<Integer> levelStack, Deque<String> titleStack) {
        if (titleStack.isEmpty()) {
            return null;
        }
        // ArrayDeque 迭代序 = 栈顶→栈底, 反转成文档序
        List<String> titles = new ArrayList<>(titleStack);
        java.util.Collections.reverse(titles);
        return String.join(" > ", titles);
    }

    /** 构建带元数据的片段(elementType/section/page) */
    private TextSegment segment(String text, ElementType type, Integer page, String section) {
        return segment(text, type, page, section, null, null, null);
    }

    /** 表格片段额外挂载行号元数据(数据行 1 起, 不含重复表头) */
    private TextSegment segment(String text, ElementType type, Integer page, String section,
                                Integer rowStart, Integer rowEnd, Integer rowTotal) {
        Metadata meta = new Metadata().put(RagServiceImpl.META_ELEMENT_TYPE, type.code());
        if (page != null) {
            meta.put(RagServiceImpl.META_PAGE, String.valueOf(page));
        }
        if (section != null && !section.isBlank()) {
            meta.put(RagServiceImpl.META_SECTION, section);
        }
        if (rowStart != null) {
            meta.put(RagServiceImpl.META_ROW_START, String.valueOf(rowStart));
            meta.put(RagServiceImpl.META_ROW_END, String.valueOf(rowEnd));
            meta.put(RagServiceImpl.META_ROW_TOTAL, String.valueOf(rowTotal));
        }
        return TextSegment.from(text, meta);
    }

    /** 字符窗口切分(带重叠): 结构分块场景下的段落兜底切分 */
    private List<String> splitByWindow(String text, int size, int overlap) {
        if (text.length() <= size) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        int step = Math.max(size - Math.min(overlap, size - 1), 1);
        for (int start = 0; start < text.length(); start += step) {
            out.add(text.substring(start, Math.min(start + size, text.length())));
            if (start + size >= text.length()) {
                break;
            }
        }
        return out;
    }
}

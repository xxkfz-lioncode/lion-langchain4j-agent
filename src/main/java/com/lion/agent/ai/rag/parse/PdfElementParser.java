package com.lion.agent.ai.rag.parse;

import com.lion.agent.ai.rag.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Rectangle;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器(二期增强): PDFBox 正文抽取 + tabula-java 表格抽取。
 * <p>
 * 每页处理流程:
 * 1. tabula 按表格线框识别该页的表格 → Markdown 表格元素(整表原子, 与 docx/md 表格同等待遇);
 * 2. 正文用 PDFTextStripper 抽取, 自定义剔除落在表格外接矩形内的文字(否则表格内容会"正文+表格"两份入库)。
 * <p>
 * 降级策略: 某页表格抽取失败(无线框表格/解析异常)仅告警, 该页正文不做剔除 —— 效果退化为一期纯文本,
 * 绝不让单页异常阻断整本解析。
 * <p>
 * 仍不支持: 图片(看图说话二期后)、纯图片扫描件(无文本明确报错, OCR 不做)、无线框表格
 * (BasicExtractionAlgorithm 误检率高, 故意不启用)。
 */
@Slf4j
@Component
public class PdfElementParser implements ElementParser {

    /** 表格区域命中判定容差(pt): 坐标系换算/基线偏移的缓冲 */
    private static final float COORD_TOLERANCE = 3f;
    /** 图片最小边(px): 小于此视为图标/装饰线, 跳过 */
    private static final int MIN_IMAGE_EDGE = 60;
    /** 图片最大像素面积(如 40MP): 超大图解码/转换内存风险, 跳过 */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    /** 单图字节上限(转 PNG 后): 超大不入库 */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** 图片提取开关(lion.rag.pdf.image-extract, 默认开: 提取无模型费用, 仅存盘+落表) */
    private final boolean imageExtractEnabled;
    /** 激活存储: 图片统一落"新上传走激活存储"的同一去处(local/minio 随配置) */
    private final FileStorage activeStorage;

    public PdfElementParser(List<FileStorage> storageImpls,
                            @Value("${lion.rag.storage-type:local}") String activeStorageType,
                            @Value("${lion.rag.pdf.image-extract:true}") boolean imageExtractEnabled) {
        // local/minio 两个实现同时注册为 Bean, 按激活配置挑选(与 RagParseWorker/RagServiceImpl 一致)
        this.activeStorage = storageImpls.stream()
                .filter(s -> activeStorageType.equals(s.type()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的存储方式: " + activeStorageType));
        this.imageExtractEnabled = imageExtractEnabled;
    }

    @Override
    public List<DocElement> parse(ParseContext ctx) throws Exception {
        List<DocElement> elements = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(ctx.getData());
             ObjectExtractor extractor = new ObjectExtractor(pdf)) {
            SpreadsheetExtractionAlgorithm tableAlg = new SpreadsheetExtractionAlgorithm();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                // ---- 1. 该页的线框表格 → Markdown 元素(并记录区域供正文剔除) ----
                List<Rectangle> tableRects = new ArrayList<>();
                try {
                    Page page = extractor.extract(p);
                    if (page != null) {
                        for (Table table : tableAlg.extract(page)) {
                            String md = tableToMarkdown(table);
                            if (md != null) {
                                elements.add(DocElement.table(md, p));
                                tableRects.add(table);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("PDF 第 {} 页表格抽取失败(该页降级为纯文本): {}",
                            p, com.lion.agent.ai.rag.util.RagUtils.rootMessage(e));
                }

                // ---- 2. 该页的内嵌图片 → FileStorage 存储 + IMAGE 元素(不参与向量化, 落 rag_image 留底) ----
                if (imageExtractEnabled) {
                    elements.addAll(extractImages(pdf, p, ctx));
                }

                // ---- 3. 正文(剔除表格区域文字) ----
                String body = extractBodyText(pdf, p, tableRects);
                if (body != null && !body.isBlank()) {
                    elements.add(DocElement.paragraph(body.strip(), p));
                }
            }
        }
        if (elements.isEmpty()) {
            throw new IllegalStateException("PDF 没有可提取的文本内容(可能是纯图片扫描件, 当前不支持 OCR)");
        }
        return elements;
    }

    /**
     * 提取该页内嵌图片: PDImageXObject → PNG 字节 → FileStorage.save(docId 防重名) → IMAGE 元素。
     * 过滤: 过小(<60px 图标/装饰线)、超大(>40MP 解码内存风险)、转 PNG 后 >10MB、解码失败(JP2/CCITT 缺 JAI)。
     * 单页异常不影响其他页。
     */
    private List<DocElement> extractImages(PDDocument pdf, int pageNum, ParseContext ctx) {
        List<DocElement> out = new ArrayList<>();
        try {
            PDPage page = pdf.getPage(pageNum - 1);
            PDResources resources = page.getResources();
            if (resources == null) {
                return out;
            }
            int seq = out.size();
            String baseName = FileStorage.sanitizeFileName(ctx.getFileName()) + "_img";
            for (PDImageXObject img : extractImagesDeep(resources, new java.util.HashSet<>())) {
                try {
                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (w < MIN_IMAGE_EDGE || h < MIN_IMAGE_EDGE
                            || (long) w * h > MAX_IMAGE_PIXELS) {
                        continue;
                    }
                    BufferedImage bi = img.getImage();
                    if (bi == null) {
                        continue;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(bi, "png", bos);
                    byte[] bytes = bos.toByteArray();
                    if (bytes.length > MAX_IMAGE_BYTES) {
                        log.info("PDF 第 {} 页图片 {}x{} 超过 10MB, 跳过存储", pageNum, w, h);
                        continue;
                    }
                    seq++;
                    String fileName = baseName + seq + ".png";
                    String objectKey = activeStorage.save(ctx.getDocId(), fileName, bytes);
                    out.add(DocElement.image(fileName, objectKey, "image/png", w, h, pageNum));
                } catch (Exception e) {
                    log.debug("PDF 第 {} 页图片提取跳过: {}", pageNum, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("PDF 第 {} 页图片资源遍历失败(跳过该页图片): {}", pageNum, e.getMessage());
        }
        return out;
    }

    /** 深度收集图片资源: 含 Form XObject 嵌套内的图片(递归, visited 防环) */
    private List<PDImageXObject> extractImagesDeep(PDResources resources, java.util.Set<COSName> visited) throws IOException {
        List<PDImageXObject> images = new ArrayList<>();
        for (COSName name : resources.getXObjectNames()) {
            if (!visited.add(name)) {
                continue;
            }
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject image) {
                images.add(image);
            } else if (xo instanceof PDFormXObject form) {
                PDResources formRes = form.getResources();
                if (formRes != null) {
                    images.addAll(extractImagesDeep(formRes, visited));
                }
            }
        }
        return images;
    }

    /**
     * 页面正文抽取: 匿名 PDFTextStripper 覆写 writeString,
     * 命中任一表格区域的字符位置则丢弃整段文本(writeString 以行为单位回调)。
     * 坐标换算: PDFTextStripper/TextPosition 是"左下原点"的 PDF 用户空间,
     * tabula Rectangle 是"顶部原点", 用页高 y = pageHeight - tp.getY() 归一。
     */
    private String extractBodyText(PDDocument pdf, int pageNum, List<Rectangle> tableRects) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                if (tableRects.isEmpty() || positions.stream().noneMatch(this::inTable)) {
                    super.writeString(text, positions);
                }
            }

            private boolean inTable(TextPosition tp) {
                float pageHeight = getCurrentPage().getMediaBox().getHeight();
                float y = pageHeight - tp.getY();
                float x = tp.getX();
                for (Rectangle r : tableRects) {
                    if (x >= r.getLeft() - COORD_TOLERANCE && x <= r.getRight() + COORD_TOLERANCE
                            && y >= r.getTop() - COORD_TOLERANCE && y <= r.getBottom() + COORD_TOLERANCE) {
                        return true;
                    }
                }
                return false;
            }
        };
        stripper.setStartPage(pageNum);
        stripper.setEndPage(pageNum);
        return stripper.getText(pdf);
    }

    /** tabula Table → Markdown 原文(列数按最大列数对齐, 分隔行插在首行后) */
    private String tableToMarkdown(Table table) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows.isEmpty()) {
            return null;
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<RectangularTextContainer> row = rows.get(r);
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(c < row.size() ? cellText(row.get(c)) : "").append(' ').append('|');
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
    private String cellText(RectangularTextContainer cell) {
        String text = cell.getText();
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ")
                .replace("|", "\\|").strip();
    }
}

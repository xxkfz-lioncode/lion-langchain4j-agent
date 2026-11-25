package com.lion.agent.ai.rag.parse;

import lombok.Getter;

/**
 * 结构化解析产出的单个文档元素 —— 元素化解析流水的最小单元。
 * <p>
 * 一次解析产出 List&lt;DocElement&gt;(保持文档出现顺序), 由 {@link StructureChunker}
 * 转成可入库的片段。类型见 {@link ElementType}。
 * <p>
 * 字段说明:
 * - text: 元素文本内容。表格 = Markdown 表格原文; 段落/标题 = 纯文本;
 * - page: 所在页码(仅 PDF 有, 其余格式为 null);
 * - titleLevel: 仅 TITLE 有效, 1~6 对应 #/##/###(docx 的 Heading1~6)。
 */
@Getter
public class DocElement {

    private final ElementType type;
    private final String text;
    /** 页码(1 起; 非 PDF 格式为 null) */
    private final Integer page;
    /** 标题层级(1~6, 仅 TITLE 类型有效, 其余为 0) */
    private final int titleLevel;
    /** 关联对象Key(仅 IMAGE: 图片在 FileStorage 中的对象Key, 后续 VLM/展示定位用) */
    private final String ref;
    /** 图片 MIME(仅 IMAGE, 如 image/png) */
    private final String imageMime;
    /** 图片宽(px, 仅 IMAGE) */
    private final Integer imageWidth;
    /** 图片高(px, 仅 IMAGE) */
    private final Integer imageHeight;

    private DocElement(ElementType type, String text, Integer page, int titleLevel,
                       String ref, String imageMime, Integer imageWidth, Integer imageHeight) {
        this.type = type;
        this.text = text;
        this.page = page;
        this.titleLevel = titleLevel;
        this.ref = ref;
        this.imageMime = imageMime;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    private DocElement(ElementType type, String text, Integer page, int titleLevel) {
        this(type, text, page, titleLevel, null, null, null, null);
    }

    public static DocElement title(String text, int level, Integer page) {
        return new DocElement(ElementType.TITLE, text, page, level);
    }

    public static DocElement paragraph(String text, Integer page) {
        return new DocElement(ElementType.PARAGRAPH, text, page, 0);
    }

    public static DocElement table(String markdown, Integer page) {
        return new DocElement(ElementType.TABLE, markdown, page, 0);
    }

    public static DocElement list(String text, Integer page) {
        return new DocElement(ElementType.LIST, text, page, 0);
    }

    /**
     * 图片元素: 已提取并存储到 FileStorage。
     * VLM 看图说话落地前不参与向量化(StructureChunker 跳过), 仅落 rag_image 表留底。
     *
     * @param fileName  图片展示名(如 "3_部署手册_img1.png")
     * @param objectKey FileStorage 对象Key
     */
    public static DocElement image(String fileName, String objectKey, String mime,
                                   Integer width, Integer height, Integer page) {
        return new DocElement(ElementType.IMAGE, fileName, page, 0, objectKey, mime, width, height);
    }
}

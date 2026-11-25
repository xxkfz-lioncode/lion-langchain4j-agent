package com.lion.agent.ai.rag.parse;

/**
 * 文档元素类型(结构化解析的产出标签):
 * 解析层不再产出"一坨纯文本", 而是逐个识别并打标的元素流,
 * 分块器({@link StructureChunker})按类型差异化处理 —— 表格原子成块、标题用于章节路径、段落聚合切分。
 */
public enum ElementType {

    /** 标题(用于构建章节路径 sectionPath, 不单独成片段) */
    TITLE("title"),

    /** 普通段落(聚合后按块大小切分) */
    PARAGRAPH("paragraph"),

    /** 表格(整表原子成块, 超长才按行分块并重复表头) */
    TABLE("table"),

    /** 列表(连续的 -、*、1. 条目聚合, 语义与段落接近但保持整体性) */
    LIST("list"),

    /** 图片(已提取并存储, 携带对象Key; VLM 看图说话落地前不参与向量化) */
    IMAGE("image"),

    /** 纯文本(兼容旧数据/无法识别类型的兜底) */
    TEXT("text");

    private final String code;

    ElementType(String code) {
        this.code = code;
    }

    /** 落库/元数据用的标识(小写字符串) */
    public String code() {
        return code;
    }

    /** 宽松解析: 未知 code 兜底 TEXT(兼容历史数据与枚举演进) */
    public static ElementType of(String code) {
        if (code == null || code.isBlank()) {
            return TEXT;
        }
        for (ElementType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return TEXT;
    }
}

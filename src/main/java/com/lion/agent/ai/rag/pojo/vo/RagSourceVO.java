package com.lion.agent.ai.rag.pojo.vo;

import lombok.Data;

/**
 * 知识问答引用来源 VO(检索层注入, 不依赖 LLM 自述出处, 引用永远真实可查)
 */
@Data
public class RagSourceVO {

    /** 文档 id(rag_document 主键, 可据此跳转/查详情) */
    private Long docId;

    /** 来源文档名 */
    private String fileName;

    /** 匹配度(0-100, 相似度 score × 100 取整; 供内部调试排序参考) */
    private Integer score;

    /** 片段摘要(截断展示, 引用条列表用) */
    private String snippet;

    /** 片段全文(点开详情/抽屉展示用) */
    private String text;
}

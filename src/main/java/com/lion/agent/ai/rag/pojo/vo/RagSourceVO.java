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

    /** PDF 页码(1 起, 引用定位"第 N 页"用; txt/md 等文本类无页码为 null) */
    private Integer page;

    /** 相关性分数(0-100): rerank 开启时 = 精排相关性 × 100; 降级时 = 向量相似度 × 100 */
    private Integer score;

    /** 向量召回相似度(0-100, 仅 BM25-only 命中或降级对比时参考; 可为 null) */
    private Integer vectorScore;

    /** 片段摘要(截断展示, 引用条列表用) */
    private String snippet;

    /** 片段全文(点开详情/抽屉展示用) */
    private String text;
}

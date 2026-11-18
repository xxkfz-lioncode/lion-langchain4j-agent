package com.lion.agent.ai.rag.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库片段表(rag_segment):
 * 解析成功后与 Milvus 向量同步落库(同一批片段), 是混合检索 BM25 关键词路的数据源,
 * 亦为后续"分段预览"等片段级功能打底。以最后成功解析的一次为准(重新解析先删旧再插新)。
 */
@Data
@TableName("rag_segment")
public class RagSegmentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 id(rag_document.id) */
    private Long docId;

    /** 片段序号(文档内从 0 递增) */
    private Integer seq;

    /** PDF 页码(1 起; txt/md 等文本类为空) */
    private Integer page;

    /** 片段原文 */
    private String content;

    private LocalDateTime createTime;
}
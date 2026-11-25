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

    /** 元素类型: title/paragraph/table/list/text(结构化解析产出) */
    private String elementType;

    /** 章节路径(标题树, 如 "部署要求 > 2.1 服务器要求") */
    private String sectionPath;

    /** 表格片段起始数据行号(1 起, 不含表头; 非表格片段为 null) */
    private Integer rowStart;

    /** 表格片段结束数据行号 */
    private Integer rowEnd;

    /** 整张表的数据行总数(拆成多块时各块相同) */
    private Integer rowTotal;

    /** 片段原文 */
    private String content;

    private LocalDateTime createTime;
}
package com.lion.agent.rag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 向量库(lion_langchain4j_docs)中的单个知识片段
 * <p>
 * 数据来自 Milvus query(不回取向量本体, 只取 text + metadata 元数据)。
 */
@Data
@Schema(description = "向量库知识片段")
public class RagSegmentVO {

    @Schema(description = "向量主键id(MilvusEmbeddingStore 写入时生成的 UUID)", example = "550e8400-e29b-41d4-a716-446655440000")
    private String id;

    @Schema(description = "片段文本内容(入库时切分后的原文)")
    private String text;

    @Schema(description = "所属文档id(入库时写入的元数据 docId)", example = "1")
    private String docId;

    @Schema(description = "来源文件名(入库时写入的元数据 fileName)", example = "langchain4j介绍.pdf")
    private String fileName;

    @Schema(description = "原始元数据 JSON(Milvus metadata 字段原文)")
    private String metadata;

    @Schema(description = "片段向量本体(仅 withVector=true 时返回, 默认不回传)")
    private List<Double> vector;

    @Schema(description = "向量维度(= vector 长度, 仅 withVector=true 时返回)", example = "1024")
    private Integer dimension;
}

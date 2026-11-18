package com.lion.agent.ai.rag.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文档片段明细 VO(rag_segment 表, 片段抽屉展示用):
 * 与向量浏览页的 RagSegmentVO(Milvus 侧)区分 —— 本 VO 来自 MySQL 片段表,
 * 是"切分原文"视角, 用于检查切分质量/核对引用溯源。
 */
@Data
@Schema(description = "文档片段明细(片段抽屉)")
public class RagDocSegmentVO {

    @Schema(description = "片段表主键", example = "101")
    private Long id;

    @Schema(description = "片段序号(文档内从 0 递增, 与向量库写入顺序一致)", example = "0")
    private Integer seq;

    @Schema(description = "PDF 页码(1 起; txt/md 等文本类为 null)", example = "1")
    private Integer page;

    @Schema(description = "片段字符数(前端展示用, 免去前端再算)", example = "286")
    private Integer charCount;

    @Schema(description = "片段原文(全量返回, 截断展示由前端 CSS line-clamp 完成)")
    private String content;
}
package com.lion.agent.ai.rag.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 文档片段明细分页 VO(片段抽屉): 后端物理分页(MyBatis-Plus 分页插件),
 * 大文档(上千片段)不会一次拉全量
 */
@Data
@Schema(description = "文档片段明细分页")
public class RagSegmentPageVO {

    @Schema(description = "过滤后片段总数", example = "52")
    private long total;

    @Schema(description = "总页数", example = "3")
    private long pages;

    @Schema(description = "当前页(1 起)", example = "1")
    private long page;

    @Schema(description = "每页条数", example = "20")
    private long size;

    @Schema(description = "当前页片段列表(按 seq 升序)")
    private List<RagDocSegmentVO> records;
}
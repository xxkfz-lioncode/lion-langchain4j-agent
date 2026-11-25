package com.lion.agent.ai.rag.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 文档图片分页 VO(MyBatis-Plus 物理分页, 图片资源页数据源) */
@Data
@Schema(description = "文档图片分页")
public class RagImagePageVO {

    @Schema(description = "过滤后图片总数", example = "36")
    private long total;

    @Schema(description = "总页数", example = "3")
    private long pages;

    @Schema(description = "当前页(1 起)", example = "1")
    private long page;

    @Schema(description = "每页条数", example = "12")
    private long size;

    @Schema(description = "当前页图片列表(按 id 倒序)")
    private List<RagImageVO> records;
}

package com.lion.agent.ai.rag.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档图片行 VO(rag_image 表 + 所属文档名): 图片资源页数据源。
 * caption 为 VLM 看图说话预留, 当前为 NULL(前端显示"待生成")。
 */
@Data
@Schema(description = "文档图片")
public class RagImageVO {

    @Schema(description = "图片记录 id(预览/下载接口入参)", example = "2")
    private Long id;

    @Schema(description = "所属文档 id", example = "18")
    private Long docId;

    @Schema(description = "所属文档名(联 rag_document 补齐)")
    private String docName;

    @Schema(description = "图片文件名", example = "RAG测试_PDF文档.pdf_img1.png")
    private String fileName;

    @Schema(description = "页码(1 起; 非 PDF 为空)", example = "3")
    private Integer page;

    @Schema(description = "MIME 类型", example = "image/png")
    private String mime;

    @Schema(description = "宽(px)", example = "1200")
    private Integer width;

    @Schema(description = "高(px)", example = "800")
    private Integer height;

    @Schema(description = "图片描述(预留 VLM 看图说话, 当前为空)")
    private String caption;

    @Schema(description = "FileStorage 对象Key(定位存储对象)")
    private String objectKey;

    @Schema(description = "入库时间")
    private LocalDateTime createTime;
}

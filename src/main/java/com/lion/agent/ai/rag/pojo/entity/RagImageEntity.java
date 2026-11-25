package com.lion.agent.ai.rag.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 文档图片表(rag_image):
 * 解析时从 PDF 等文档提取的内嵌图片(已存 FileStorage), 按文档维度留底。
 * caption 预留给后续 VLM 看图说话(填充后可生成文本片段参与向量检索, 无需重新解析)。
 * 重解析时先删旧行与存储对象再插新(与 rag_segment "以最后成功一次为准"语义一致)。
 */
@Data
@TableName("rag_image")
public class RagImageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 id(rag_document.id) */
    private Long docId;

    /** 页码(1 起; 非 PDF 格式为空) */
    private Integer page;

    /** FileStorage 对象Key(local 相对路径 / minio 对象 Key) */
    private String objectKey;

    /** 图片展示文件名 */
    private String fileName;

    /** MIME 类型(当前统一 image/png) */
    private String mime;

    /** 宽(px) */
    private Integer width;

    /** 高(px) */
    private Integer height;

    /** 图片描述(预留: VLM 看图说话结果, 未实现前为 NULL) */
    private String caption;

    private LocalDateTime createTime;
}

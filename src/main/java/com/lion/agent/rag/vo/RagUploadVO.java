package com.lion.agent.rag.vo;

import lombok.Data;

/**
 * RAG 知识上传结果视图对象
 */
@Data
public class RagUploadVO {

    /** 文档id(即向量片段 docId 元数据, 列表/删除接口用) */
    private Long id;

    /** 上传文件名 */
    private String fileName;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 文件类型(扩展名小写, 如 pdf/txt/md) */
    private String fileType;

    /** 原始文件保存路径(相对运行目录, 如 upload/1_demo.pdf) */
    private String filePath;

    /** 本次入库片段数(即向量条数) */
    private Integer segments;

    /** 本次使用的切分方式编码(见 SplitterType) */
    private String splitterType;

    /** 本次切分块大小(字符) */
    private Integer segmentSize;

    /** 本次相邻块重叠(字符) */
    private Integer overlapSize;

    /** 当前知识库总片段数 */
    private Long total;
}

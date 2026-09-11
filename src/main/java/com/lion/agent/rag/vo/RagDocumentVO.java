package com.lion.agent.rag.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库文档列表项(前端文件列表展示用)
 */
@Data
public class RagDocumentVO {

    /** 文档id(删除接口用) */
    private Long id;

    /** 文件名(带扩展名) */
    private String fileName;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 文件类型(扩展名小写, 如 pdf/txt/md) */
    private String fileType;

    /** 上传文件保存路径(相对运行目录, 如 upload/1_demo.pdf) */
    private String filePath;

    /** 入库片段数(向量条数) */
    private Integer segmentCount;

    /** 切分方式编码(见 SplitterType, 如 recursive/paragraph/regex) */
    private String splitterType;

    /** 切分块大小(字符) */
    private Integer segmentSize;

    /** 相邻块重叠(字符) */
    private Integer overlapSize;

    /** 正则切分使用的正则(仅 regex 方式有值) */
    private String splitterPattern;

    /** 上传时间 */
    private LocalDateTime createTime;
}

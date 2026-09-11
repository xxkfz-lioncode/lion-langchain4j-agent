package com.lion.agent.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库文档元信息实体(追加式入库后每个文档一行)
 * <p>
 * 对应 rag_document 表; 文档主键 id 同时写入向量片段 TextSegment 的
 * docId 元数据, 用于前端文件列表展示 / 按文件删除向量 / 按文件检索过滤。
 */
@Data
@TableName("rag_document")
public class RagDocumentEntity {

    /** 主键(同时作为向量片段 docId 元数据) */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件名(带扩展名) */
    private String fileName;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 文件类型(扩展名小写, 如 pdf/txt/md) */
    private String fileType;

    /** 切分方式编码(见 SplitterType, 如 recursive/paragraph/regex) */
    private String splitterType;

    /** 切分块大小(字符) */
    private Integer segmentSize;

    /** 相邻块重叠(字符) */
    private Integer overlapSize;

    /** 正则切分使用的正则(仅 regex 方式有值, 其余存空串) */
    private String splitterPattern;

    /** 上传文件保存路径(相对运行目录, 如 upload/1_demo.pdf; 删除文档时一并删除) */
    private String filePath;

    /** 入库片段数(向量条数) */
    private Integer segmentCount;

    /** 状态: 1 成功 / 0 失败 */
    private Integer status;

    /** 上传时间(数据库自动维护) */
    private LocalDateTime createTime;

    /** 更新时间(数据库自动维护) */
    private LocalDateTime updateTime;
}

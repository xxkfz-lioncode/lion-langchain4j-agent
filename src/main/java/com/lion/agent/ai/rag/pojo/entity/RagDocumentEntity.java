package com.lion.agent.ai.rag.pojo.entity;

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

    /**
     * 原始文件对象Key(local = 相对路径如 upload/1_demo.pdf; minio = 对象Key如 rag/2026/11/1_demo.pdf)
     * 语义由 storage_type 决定; 解析读取/删除/下载时据此路由到对应存储实现
     */
    private String filePath;

    /** 存储方式: local 本地磁盘 / minio 对象存储(新上传文件由 lion.rag.storage-type 决定) */
    private String storageType;

    /** 入库片段数(向量条数) */
    private Integer segmentCount;

    /** 解析状态: 0 待解析 / 1 已解析(已入库) / 2 解析中(异步任务执行) / 3 解析失败 */
    private Integer status;

    /** 解析进度(0-100, 仅解析中有意义; 由异步任务按向量化批次更新) */
    private Integer progress;

    /** 最近一次解析失败的错误原因(成功/解析中为空) */
    private String errorMsg;

    /** 累计解析触发次数(含失败, 观测用) */
    private Integer retryCount;

    /** 最近一次解析完成时间 */
    private LocalDateTime parseTime;

    /** 上传时间(数据库自动维护) */
    private LocalDateTime createTime;

    /** 更新时间(数据库自动维护) */
    private LocalDateTime updateTime;
}

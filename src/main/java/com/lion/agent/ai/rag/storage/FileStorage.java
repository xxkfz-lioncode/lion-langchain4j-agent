package com.lion.agent.ai.rag.storage;

/**
 * RAG 知识文档原始文件存储抽象
 * <p>
 * 上传的原始文档(待解析阶段需要)支持两种落地方式, 由配置 {@code lion.rag.storage-type} 决定:
 * <ul>
 *   <li>{@link LocalFileStorage}  — 本地 upload 目录(默认, 零外部依赖, 开发环境友好);</li>
 *   <li>{@link MinioFileStorage}  — MinIO 对象存储(S3 兼容, 支持后端多实例共享/数据独立持久化)。</li>
 * </ul>
 * 两个实现<b>同时注册为 Bean</b>(不按条件互斥): 新上传走"激活存储"(配置指定),
 * 而存量文件按 rag_document.storage_type 路由到对应实现读取/删除, 保证切换存储方式后历史数据仍可用。
 * <p>
 * 每条文档记录的 {@code file_path} 字段存的是本接口返回的"对象Key":
 * local 实现存相对路径(如 upload/3_需求.pdf), minio 实现存对象 Key(如 rag/2026/11/3_需求.pdf)。
 */
public interface FileStorage {

    /** 本地存储实现标识 */
    String TYPE_LOCAL = "local";
    /** MinIO 存储实现标识 */
    String TYPE_MINIO = "minio";

    /**
     * 存储实现标识(与 rag_document.storage_type 对应): local / minio
     */
    String type();

    /**
     * 保存上传的原始文件
     *
     * @param docId    文档 id(rag_document 主键, 用于对象Key防重名)
     * @param fileName 原始文件名(带扩展名)
     * @param data     文件二进制内容
     * @return 对象Key(存库 file_path, 供解析读取/删除/下载)
     */
    String save(Long docId, String fileName, byte[] data);

    /**
     * 读取文件全部字节(文档大小上限 20MB, 全量读取可接受)
     *
     * @param objectKey 保存时返回的对象Key
     * @return 文件内容; 文件不存在抛业务异常
     */
    byte[] load(String objectKey);

    /**
     * 删除文件; 文件不存在视为已删除(幂等); 失败仅告警不抛出(不阻断文档删除主流程)
     *
     * @param objectKey 保存时返回的对象Key
     */
    void delete(String objectKey);

    /**
     * 文件是否存在
     */
    boolean exists(String objectKey);

    /**
     * 去掉路径部分与非法字符(防目录穿越 / 对象Key非法字符), 两个存储实现共用
     */
    static String sanitizeFileName(String fileName) {
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }
}

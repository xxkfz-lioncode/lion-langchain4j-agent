package com.lion.agent.ai.rag.storage;

import com.lion.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地磁盘存储实现(默认): 原始文件保存到配置的 upload 目录(相对运行目录, 已被 .gitignore 忽略)
 * <p>
 * 对象Key = 相对运行目录的路径, 如 {@code upload/3_需求文档.pdf}(文件名加 docId 前缀防重名),
 * 与抽象前的 rag_document.file_path 格式完全一致, 因此存量本地数据无需迁移即可继续读取。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    /** 上传目录(绝对路径, 构造时归一化) */
    private final Path dir;
    /** 配置的原始相对目录串(用于生成存库的相对路径, 与历史数据格式一致) */
    private final String dirRaw;

    public LocalFileStorage(@Value("${lion.rag.upload-dir:upload}") String uploadDir) {
        this.dirRaw = uploadDir;
        this.dir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String type() {
        return TYPE_LOCAL;
    }

    @Override
    public String save(Long docId, String fileName, byte[] data) {
        try {
            Files.createDirectories(dir);
            String objectKey = dirRaw.replace('\\', '/') + "/" + docId + "_" + FileStorage.sanitizeFileName(fileName);
            Path target = resolve(objectKey);
            // 防目录穿越: 归一化后必须仍落在上传目录内
            if (!target.startsWith(dir)) {
                throw new BusinessException("文件名非法: " + fileName);
            }
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return objectKey;
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] load(String objectKey) {
        Path path = resolve(objectKey);
        if (!Files.exists(path)) {
            throw new BusinessException("文件不存在(" + objectKey + "), 请删除该文档后重新上传");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage());
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (Exception e) {
            log.warn("删除本地文件[{}]失败: {}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return objectKey != null && !objectKey.isBlank() && Files.exists(resolve(objectKey));
    }

    /**
     * 对象Key → 实际路径: 相对路径按运行目录解析(与历史 file_path 格式兼容);
     * 兜底: 若按运行目录找不到, 再按"上传目录 + 文件名"找一次(兼容手工改过 upload-dir 的场景)
     */
    private Path resolve(String objectKey) {
        Path path = Path.of(objectKey);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path byWorkdir = Path.of("").toAbsolutePath().resolve(path).normalize();
        if (Files.exists(byWorkdir) || byWorkdir.startsWith(dir)) {
            return byWorkdir;
        }
        return dir.resolve(path.getFileName()).normalize();
    }
}

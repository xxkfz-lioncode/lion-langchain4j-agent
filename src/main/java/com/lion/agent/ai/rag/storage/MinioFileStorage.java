package com.lion.agent.ai.rag.storage;

import com.lion.agent.ai.rag.util.RagUtils;
import com.lion.agent.common.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MinIO 对象存储实现(S3 兼容): 原始文件存入配置的 bucket, 支持后端多实例共享与数据独立持久化
 * <p>
 * 对象Key 格式: {@code rag/yyyy/MM/{docId}_{文件名}}(按月分目录, docId 前缀防重名)。
 * <ul>
 *   <li>MinioClient 惰性初始化(首次使用才建连接并确保 bucket 存在), storage-type=local 时零开销、
 *       未配置连接参数也不影响启动;</li>
 *   <li>bucket 权限保持私有, 外部获取文件统一走后端鉴权接口(下载/解析), 不开放匿名访问;</li>
 *   <li>连接参数来自 .env(MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET)。</li>
 * </ul>
 */
@Slf4j
@Component
public class MinioFileStorage implements FileStorage {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;

    /** 惰性初始化的客户端(双重检查锁; MinioClient.build() 不发起网络请求, 连接失败在使用时暴露) */
    private volatile MinioClient client;

    public MinioFileStorage(@Value("${lion.minio.endpoint:}") String endpoint,
                            @Value("${lion.minio.access-key:}") String accessKey,
                            @Value("${lion.minio.secret-key:}") String secretKey,
                            @Value("${lion.minio.bucket:lion-rag}") String bucket) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
    }

    @Override
    public String type() {
        return TYPE_MINIO;
    }

    @Override
    @SuppressWarnings("resource") // MinioClient 是应用级单例(AutoCloseable 仅因 SDK 8.5+), 故意不关, 抑制 try-with-resources 检查
    public String save(Long docId, String fileName, byte[] data) {
        String objectKey = "rag/"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + docId + "_" + FileStorage.sanitizeFileName(fileName);
        try {
            client().putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build());
            return objectKey;
        } catch (Exception e) {
            throw new BusinessException("文件保存到 MinIO 失败: " + RagUtils.rootMessage(e));
        }
    }

    @Override
    @SuppressWarnings("resource") // 同上: 单例客户端不关闭
    public byte[] load(String objectKey) {
        try (InputStream in = client().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return readAll(in);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new BusinessException("文件不存在(" + objectKey + "), 请删除该文档后重新上传");
            }
            throw new BusinessException("文件从 MinIO 读取失败: " + RagUtils.rootMessage(e));
        } catch (Exception e) {
            throw new BusinessException("文件从 MinIO 读取失败: " + RagUtils.rootMessage(e));
        }
    }

    @Override
    @SuppressWarnings("resource") // 同上: 单例客户端不关闭
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // 删除失败仅告警, 不阻断文档删除主流程(对象残留可在 MinIO 控制台手工清理)
            log.warn("删除 MinIO 对象[{}]失败: {}", objectKey, RagUtils.rootMessage(e));
        }
    }

    @Override
    @SuppressWarnings("resource") // 同上: 单例客户端不关闭
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            client().statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            log.warn("检查 MinIO 对象[{}]失败: {}", objectKey, RagUtils.rootMessage(e));
            return false;
        }
    }

    /** 惰性构建客户端并确保 bucket 存在(首次使用时执行一次) */
    private MinioClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (endpoint == null || endpoint.isBlank()
                            || accessKey == null || accessKey.isBlank()) {
                        throw new BusinessException(
                                "MinIO 未配置: 请在 .env 填写 MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY "
                                        + "并设置 lion.rag.storage-type=minio");
                    }
                    MinioClient built = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .build();
                    ensureBucket(built);
                    client = built;
                }
            }
        }
        return client;
    }

    /** bucket 不存在则自动创建(私有读写, 不设匿名访问策略) */
    private void ensureBucket(MinioClient c) {
        try {
            if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                c.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket[{}]不存在, 已自动创建", bucket);
            }
        } catch (Exception e) {
            throw new BusinessException("MinIO bucket 初始化失败: " + RagUtils.rootMessage(e));
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}

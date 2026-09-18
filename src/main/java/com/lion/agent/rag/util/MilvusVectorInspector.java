package com.lion.agent.rag.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.rag.dto.MilvusConnectionDTO;
import com.lion.agent.rag.vo.RagSegmentVO;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Milvus 向量库查看工具(支持任意连接)
 * <p>
 * 用来"人肉检查"Milvus 里到底存了什么: 列出数据库/集合、查看集合结构、分页查看片段内容与元数据。
 * <p>
 * 为什么不用注入的 {@code EmbeddingStore}: LangChain4j 的 EmbeddingStore 接口只有
 * search/add/remove, <b>没有"列出全部内容/列举集合"的 API</b>, 只能用 Milvus 原生 SDK。
 * <p>
 * 连接规则:
 * 1. {@link MilvusConnectionDTO} 字段为空时回退到 application.yml 的 {@code langchain4j.milvus.*};
 * 2. 客户端按 {@code host:port/database} 缓存复用(见 {@link #client}), 避免每次请求都新建 gRPC 连接;
 * 3. 连不通时抛业务异常并带上地址, 前端直接展示, 不污染应用启动流程。
 * <p>
 * 注意:
 * 1. 默认不回传向量本体(省流量), {@code withVector=true} 时才取 vector 字段(1024 维 ≈ 4KB/条);
 * 2. metadata 是 JSON 字符串, 形如 {"docId":"1","fileName":"xx.pdf"}, 解析后填充 VO;
 * 3. Milvus 单次 query 的 offset+limit 上限 16384, 深分页会报错 —— 排查工具够用;
 * 4. 客户端懒加载: Milvus 没起也不影响应用启动, 第一次调用才连。
 */
@Slf4j
@Component
public class MilvusVectorInspector {

    /** 集合字段名(MilvusEmbeddingStore 建表时固定): 主键 */
    private static final String FIELD_ID = "id";
    /** 集合字段名: 片段文本 */
    private static final String FIELD_TEXT = "text";
    /** 集合字段名: 元数据 JSON */
    private static final String FIELD_METADATA = "metadata";
    /** 集合字段名: 向量本体(FLOAT_VECTOR, 默认 1024 维) */
    private static final String FIELD_VECTOR = "vector";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${langchain4j.milvus.host:127.0.0.1}")
    private String host;

    @Value("${langchain4j.milvus.port:19530}")
    private int port;

    @Value("${langchain4j.milvus.collection-name:lion_langchain4j_docs}")
    private String collectionName;

    @Value("${langchain4j.milvus.database-name:default}")
    private String databaseName;

    /** 客户端缓存: key = host:port/database, 复用 gRPC 连接(多连接切换时不会反复建连) */
    private final Map<String, MilvusServiceClient> clientCache = new ConcurrentHashMap<>();

    /** 集合结构缓存: key = host:port/database/collection, 避免每次查询都 describeCollection */
    private final Map<String, SchemaInfo> schemaCache = new ConcurrentHashMap<>();

    // ====================== 对外能力 ======================

    /**
     * 项目默认连接(读取 application.yml 的 langchain4j.milvus.*), 供前端"默认加载当前项目连接信息"
     */
    public MilvusConnectionDTO defaultConnection() {
        MilvusConnectionDTO dto = new MilvusConnectionDTO();
        dto.setHost(host);
        dto.setPort(port);
        dto.setDatabase(databaseName);
        dto.setCollection(collectionName);
        return dto;
    }

    /**
     * 列出连接下的所有数据库(Milvus listDatabases)
     */
    public List<String> listDatabases(MilvusConnectionDTO conn) {
        // SDK 2.4.x: showDatabases 已更名 listDatabases, 返回 ListDatabasesResponse
        ListDatabasesResponse data = call(conn, "查询数据库列表", () -> client(conn).listDatabases());
        List<String> names = new ArrayList<>(data.getDbNamesList());
        // 兜底: 个别版本不返回默认库名, 保证下拉框里至少能看到当前库
        String current = resolveDatabase(conn);
        if (names.stream().noneMatch(n -> n.equals(current))) {
            names.add(current);
        }
        return names;
    }

    /**
     * 列出指定数据库下的所有集合(showCollections)
     */
    public List<String> listCollections(MilvusConnectionDTO conn) {
        ShowCollectionsResponse data = call(conn, "查询集合列表",
                () -> client(conn).showCollections(ShowCollectionsParam.newBuilder()
                        .withDatabaseName(resolveDatabase(conn))
                        .build()));
        return new ArrayList<>(data.getCollectionNamesList());
    }

    /**
     * 集合概览: 集合名 / 片段数 / 向量维度 / 字段列表
     */
    public Map<String, Object> collectionInfo(MilvusConnectionDTO conn) {
        String coll = resolveCollection(conn);
        DescribeCollectionResponse detail = call(conn, "查看集合信息",
                () -> client(conn).describeCollection(DescribeCollectionParam.newBuilder()
                        .withDatabaseName(resolveDatabase(conn))
                        .withCollectionName(coll)
                        .build()));

        List<String> fields = new ArrayList<>();
        Integer dimension = null;
        for (FieldSchema field : detail.getSchema().getFieldsList()) {
            fields.add(field.getName());
            if (FIELD_VECTOR.equals(field.getName())) {
                dimension = readDimension(field);
            }
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("collection", coll);
        info.put("fields", fields);
        info.put("dimension", dimension);
        // 片段数用 count(*) 聚合查(Milvus getCollectionStatistics 的 row_count 只统计已 flush 数据,
        // 而本项目 auto-flush-on-insert=false, 未落盘的数据统计不到)
        info.put("rowCount", count(conn, null));
        return info;
    }

    /**
     * 统计片段总数(可按文件名过滤)
     *
     * @param fileName 文件名精确匹配, null/空 = 不过滤
     */
    public long count(MilvusConnectionDTO conn, String fileName) {
        QueryResults data = call(conn, "统计片段数",
                () -> client(conn).query(QueryParam.newBuilder()
                        .withDatabaseName(resolveDatabase(conn))
                        .withCollectionName(resolveCollection(conn))
                        .withExpr(buildExpr(conn, fileName))
                        .withOutFields(Collections.singletonList("count(*)"))
                        .build()));
        List<QueryResultsWrapper.RowRecord> records = new QueryResultsWrapper(data).getRowRecords();
        if (records.isEmpty()) {
            return 0L;
        }
        Object count = records.get(0).get("count(*)");
        return count == null ? 0L : ((Number) count).longValue();
    }

    /**
     * 分页列出片段内容(默认不回取向量本体)
     *
     * @param offset   起始偏移(0 起)
     * @param limit    每页条数(1~100)
     * @param fileName 文件名精确过滤, null/空 = 全部
     */
    public List<RagSegmentVO> list(MilvusConnectionDTO conn, long offset, long limit, String fileName) {
        return list(conn, offset, limit, fileName, false);
    }

    /**
     * 分页列出片段内容
     *
     * @param withVector true = 同时回取向量本体(1024 维 float, 每条约 4KB, 按需开启)
     */
    public List<RagSegmentVO> list(MilvusConnectionDTO conn, long offset, long limit,
                                   String fileName, boolean withVector) {
        SchemaInfo schema = schema(conn);
        // 输出列按真实 schema 动态取(主键字段名可能是 id/pk/其他, 不能写死)
        List<String> outFields = new ArrayList<>();
        outFields.add(schema.pkField());
        if (schema.textField() != null) {
            outFields.add(schema.textField());
        }
        if (schema.metadataField() != null) {
            outFields.add(schema.metadataField());
        }
        if (withVector && schema.vectorField() != null) {
            outFields.add(schema.vectorField());
        }
        QueryResults data = call(conn, "查询片段",
                () -> client(conn).query(QueryParam.newBuilder()
                        .withDatabaseName(resolveDatabase(conn))
                        .withCollectionName(resolveCollection(conn))
                        .withExpr(buildExpr(conn, fileName))
                        .withOutFields(outFields)
                        .withOffset(Math.max(0, offset))
                        .withLimit(Math.max(1, limit))
                        .build()));
        return new QueryResultsWrapper(data)
                .getRowRecords()
                .stream()
                .map(record -> toVo(record, schema, withVector))
                .toList();
    }

    // ====================== 内部实现 ======================

    /**
     * 组装过滤表达式。
     * <p>
     * 不能写死 {@code id != ""} 当恒真条件: 不同集合的主键字段名/类型不一样(id/pk/自增 int64...),
     * 引用不存在的字段 Milvus 会直接报 "field id not exist"。
     * 所以先看真实 schema: 按主键类型拼恒真表达式, 有文件名时按 metadata JSON 字段匹配。
     */
    private String buildExpr(MilvusConnectionDTO conn, String fileName) {
        SchemaInfo schema = schema(conn);
        if (fileName == null || fileName.isBlank()) {
            // 主键是字符串 → != "" 恒真; 是数值 → >= 0 恒真
            return schema.pkIsString()
                    ? schema.pkField() + " != \"\""
                    : schema.pkField() + " >= 0";
        }
        if (schema.metadataField() == null) {
            throw new BusinessException("集合 " + resolveCollection(conn)
                    + " 没有 metadata 字段, 无法按文件名过滤, 请清空过滤条件后重试");
        }
        // 转义引号/反斜杠, 防止文件名破坏表达式语法
        String safe = fileName.trim().replace("\\", "\\\\").replace("\"", "\\\"");
        return schema.metadataField() + "[\"fileName\"] == \"" + safe + "\"";
    }

    /** Milvus 行记录 → VO(按真实 schema 取列; 解析 metadata JSON 提取 docId/fileName; 按需解析向量) */
    private RagSegmentVO toVo(QueryResultsWrapper.RowRecord record, SchemaInfo schema, boolean withVector) {
        RagSegmentVO vo = new RagSegmentVO();
        vo.setId(str(record.get(schema.pkField())));
        if (schema.textField() != null) {
            vo.setText(str(record.get(schema.textField())));
        }
        if (withVector && schema.vectorField() != null) {
            Object vector = record.get(schema.vectorField());
            // Milvus FLOAT_VECTOR 查询结果为 List<Float>
            if (vector instanceof List<?> list) {
                vo.setVector(list.stream()
                        .map(v -> v == null ? null : ((Number) v).doubleValue())
                        .toList());
                vo.setDimension(vo.getVector().size());
            }
        }
        if (schema.metadataField() != null) {
            String meta = str(record.get(schema.metadataField()));
            vo.setMetadata(meta);
            if (meta != null && !meta.isBlank()) {
                try {
                    Map<String, Object> map = objectMapper.readValue(meta, Map.class);
                    vo.setDocId(str(map.get("docId")));
                    vo.setFileName(str(map.get("fileName")));
                } catch (Exception e) {
                    log.warn("片段[{}]元数据解析失败: {}", vo.getId(), e.getMessage());
                }
            }
        }
        return vo;
    }

    /**
     * 取(并缓存)集合的真实结构: 主键字段名/类型 + text/metadata/vector 字段名。
     * <p>
     * 优先按 MilvusEmbeddingStore 的约定名(id/text/metadata/vector)识别;
     * 没有约定名时按数据类型兜底猜: FLOAT_VECTOR → 向量列, VarChar → 文本/元数据列。
     */
    private SchemaInfo schema(MilvusConnectionDTO conn) {
        String key = endpoint(conn) + "/" + resolveDatabase(conn) + "/" + resolveCollection(conn);
        return schemaCache.computeIfAbsent(key, k -> {
            DescribeCollectionResponse detail = call(conn, "查看集合结构",
                    () -> client(conn).describeCollection(DescribeCollectionParam.newBuilder()
                            .withDatabaseName(resolveDatabase(conn))
                            .withCollectionName(resolveCollection(conn))
                            .build()));
            String pkField = FIELD_ID;
            boolean pkIsString = true;
            String textField = null;
            String metadataField = null;
            String vectorField = null;
            for (FieldSchema field : detail.getSchema().getFieldsList()) {
                String name = field.getName();
                if (field.getIsPrimaryKey()) {
                    pkField = name;
                    pkIsString = field.getDataType() == DataType.VarChar;
                }
                if (FIELD_TEXT.equals(name)) {
                    textField = name;
                } else if (FIELD_METADATA.equals(name)) {
                    metadataField = name;
                } else if (FIELD_VECTOR.equals(name)) {
                    vectorField = name;
                }
            }
            // 兜底: 非约定命名的集合, 按数据类型识别
            for (FieldSchema field : detail.getSchema().getFieldsList()) {
                if (field.getIsPrimaryKey()) {
                    continue;
                }
                if (vectorField == null && field.getDataType() == DataType.FloatVector) {
                    vectorField = field.getName();
                } else if (field.getDataType() == DataType.VarChar) {
                    if (textField == null) {
                        textField = field.getName();
                    } else if (metadataField == null) {
                        metadataField = field.getName();
                    }
                }
            }
            log.debug("集合[{}]结构: pk={}, text={}, metadata={}, vector={}",
                    resolveCollection(conn), pkField, textField, metadataField, vectorField);
            return new SchemaInfo(pkField, pkIsString, textField, metadataField, vectorField);
        });
    }

    /** 集合结构摘要: 主键字段及其类型 + 识别出的 text/metadata/vector 字段(可能为 null) */
    private record SchemaInfo(String pkField, boolean pkIsString,
                              String textField, String metadataField, String vectorField) {
    }

    /** 从 vector 字段的参数里读维度(dim) */
    private Integer readDimension(FieldSchema field) {
        // SDK 2.4.x: 字段参数拆成 type_params / index_params 两组, dim 存在 type_params 里
        List<KeyValuePair> params = new ArrayList<>(field.getTypeParamsList());
        params.addAll(field.getIndexParamsList());
        for (KeyValuePair pair : params) {
            if ("dim".equals(pair.getKey())) {
                try {
                    return Integer.valueOf(pair.getValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---- 连接参数解析: 页面传了就用页面的, 没传用项目默认 ----

    private String resolveHost(MilvusConnectionDTO conn) {
        return conn != null && notBlank(conn.getHost()) ? conn.getHost().trim() : host;
    }

    private int resolvePort(MilvusConnectionDTO conn) {
        return conn != null && conn.getPort() != null && conn.getPort() > 0 ? conn.getPort() : port;
    }

    private String resolveDatabase(MilvusConnectionDTO conn) {
        return conn != null && notBlank(conn.getDatabase()) ? conn.getDatabase().trim() : databaseName;
    }

    private String resolveCollection(MilvusConnectionDTO conn) {
        return conn != null && notBlank(conn.getCollection()) ? conn.getCollection().trim() : collectionName;
    }

    private String endpoint(MilvusConnectionDTO conn) {
        return resolveHost(conn) + ":" + resolvePort(conn);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 取(或懒创建)指定连接的客户端: 按 host:port/database 缓存, 多连接切换不会反复建 gRPC 连接
     */
    private MilvusServiceClient client(MilvusConnectionDTO conn) {
        String database = resolveDatabase(conn);
        String key = endpoint(conn) + "/" + database;
        return clientCache.computeIfAbsent(key, k -> {
            MilvusServiceClient created = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(resolveHost(conn))
                    .withPort(resolvePort(conn))
                    .withDatabaseName(database)
                    .build());
            log.info("Milvus 查看工具已连接 {} (database={})", key, database);
            return created;
        });
    }

    /** 统一调用 + 异常包装: 连不通/查失败都给前端一句人话 */
    private <T> T call(MilvusConnectionDTO conn, String action, Supplier<R<T>> supplier) {
        R<T> resp;
        try {
            resp = supplier.get();
        } catch (Exception e) {
            throw new BusinessException("连接 Milvus 失败(" + endpoint(conn) + "): " + e.getMessage()
                    + "; 请检查地址/端口/网络, 以及 Milvus 是否已启动");
        }
        if (resp == null || resp.getStatus() != R.Status.Success.getCode()) {
            throw new BusinessException(action + "失败(" + endpoint(conn) + "): "
                    + (resp == null ? "无响应" : resp.getMessage()));
        }
        return resp.getData();
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    @PreDestroy
    public void close() {
        clientCache.forEach((key, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 Milvus 客户端[{}]失败: {}", key, e.getMessage());
            }
        });
        clientCache.clear();
    }
}

package com.lion.agent.rag.controller;

import com.lion.agent.common.Result;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.rag.dto.MilvusConnectionDTO;
import com.lion.agent.rag.util.MilvusVectorInspector;
import com.lion.agent.rag.vo.RagSegmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库查询接口(独立于 RAG 业务接口, 供前端"向量库查询"页面随时翻看入库数据)
 * <p>
 * 与 {@link RagController} 的分工:
 * - RagController: 业务操作(上传/问答/删除);
 * - 本类: 只读排查, 直接查 Milvus 的数据库/集合/片段, 不涉及向量计算与业务写入。
 * <p>
 * 连接参数(host/port/database/collection)全部可选:
 * 不传 = 用 application.yml 的项目默认({@code langchain4j.milvus.*}), 传了 = 查指定实例。
 * 登录后可用(位于 /api/** 自动纳入 Sa-Token 登录校验)。
 */
@Tag(name = "向量库查询", description = "查看 Milvus 数据库/集合/片段内容, 支持自定义连接(需登录)")
@RestController
@RequestMapping("/api/vector")
public class VectorStoreController {

    private final MilvusVectorInspector milvusVectorInspector;

    public VectorStoreController(MilvusVectorInspector milvusVectorInspector) {
        this.milvusVectorInspector = milvusVectorInspector;
    }

    // ====================== 连接与结构 ======================

    /**
     * 页面初始化: 返回项目默认连接 + 该连接下的数据库列表 + 集合列表
     * <p>
     * 连不上 Milvus 不报错, 而是返回 connected=false + error 文案, 让页面能正常渲染并提示用户改连接。
     */
    @Operation(summary = "默认连接信息 + 库/集合列表",
            description = "读取 application.yml 的项目默认连接(langchain4j.milvus.*), 并返回该连接下的"
                    + "数据库列表与集合列表; Milvus 连不上时 connected=false 且 error 带原因, 不抛错")
    @GetMapping("/connection")
    public Result<Map<String, Object>> connection() {
        MilvusConnectionDTO defaults = milvusVectorInspector.defaultConnection();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("defaultConnection", defaults);
        try {
            data.put("databases", milvusVectorInspector.listDatabases(defaults));
            data.put("collections", milvusVectorInspector.listCollections(defaults));
            data.put("connected", true);
        } catch (BusinessException e) {
            data.put("databases", List.of());
            data.put("collections", List.of());
            data.put("connected", false);
            data.put("error", e.getMessage());
        }
        return Result.ok(data);
    }

    /**
     * 指定连接下的数据库列表
     */
    @Operation(summary = "数据库列表", description = "查询指定 Milvus 连接下的所有数据库(showDatabases)")
    @GetMapping("/databases")
    public Result<List<String>> databases(@ModelAttribute MilvusConnectionDTO conn) {
        return Result.ok(milvusVectorInspector.listDatabases(conn));
    }

    /**
     * 指定连接/数据库下的集合列表
     */
    @Operation(summary = "集合列表", description = "查询指定数据库下的所有集合(showCollections)")
    @GetMapping("/collections")
    public Result<List<String>> collections(@ModelAttribute MilvusConnectionDTO conn) {
        return Result.ok(milvusVectorInspector.listCollections(conn));
    }

    /**
     * 集合概览(片段数 / 向量维度 / 字段列表)
     */
    @Operation(summary = "集合概览", description = "集合名、片段总数(count(*))、向量维度、字段列表")
    @GetMapping("/collection-info")
    public Result<Map<String, Object>> collectionInfo(@ModelAttribute MilvusConnectionDTO conn) {
        return Result.ok(milvusVectorInspector.collectionInfo(conn));
    }

    // ====================== 片段数据 ======================

    /**
     * 分页查询向量库片段(id/text/metadata, 不回取向量本体)
     */
    @Operation(summary = "分页查询片段",
            description = "查询指定集合中的原始片段(id/text/metadata), 不涉及向量计算; "
                    + "fileName 可选, 用于只看某个文件切出来的片段; "
                    + "withVector=true 时额外回传每条片段的向量本体(1024 维 float, 约 4KB/条, 流量较大按需开启)。"
                    + "注意 offset+limit 受 Milvus 单次 query 上限 16384 限制")
    @GetMapping("/segments")
    public Result<Map<String, Object>> segments(
            @ModelAttribute MilvusConnectionDTO conn,
            @Parameter(description = "每页条数(1~100)", example = "20")
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @Parameter(description = "起始偏移(0 起)", example = "0")
            @RequestParam(value = "offset", defaultValue = "0") Integer offset,
            @Parameter(description = "按文件名精确过滤(可选, 与入库时的文件名一致)")
            @RequestParam(value = "fileName", required = false) String fileName,
            @Parameter(description = "是否回取向量本体(默认 false, 开启后每条多约 4KB 流量)")
            @RequestParam(value = "withVector", defaultValue = "false") boolean withVector) {
        int lim = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        long off = offset == null ? 0 : Math.max(0, offset);
        long total = milvusVectorInspector.count(conn, fileName);
        List<RagSegmentVO> items = total == 0
                ? List.of()
                : milvusVectorInspector.list(conn, off, lim, fileName, withVector);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("offset", off);
        data.put("limit", lim);
        data.put("fileName", fileName);
        data.put("withVector", withVector);
        data.put("items", items);
        return Result.ok(data);
    }

    /**
     * 片段总数(可按文件名过滤)
     */
    @Operation(summary = "片段总数", description = "指定集合的片段总数, fileName 可选过滤")
    @GetMapping("/count")
    public Result<Long> count(
            @ModelAttribute MilvusConnectionDTO conn,
            @Parameter(description = "按文件名精确过滤(可选)")
            @RequestParam(value = "fileName", required = false) String fileName) {
        return Result.ok(milvusVectorInspector.count(conn, fileName));
    }
}

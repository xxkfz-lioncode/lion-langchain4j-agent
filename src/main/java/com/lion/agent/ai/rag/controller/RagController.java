package com.lion.agent.ai.rag.controller;

import com.lion.agent.ai.rag.pojo.dto.RagChatRequest;
import com.lion.agent.ai.rag.util.RagUtils;
import com.lion.agent.common.R;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.ai.rag.service.RagService;
import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.pojo.vo.RagAnswerVO;
import com.lion.agent.ai.rag.pojo.vo.RagDocumentVO;
import com.lion.agent.ai.rag.pojo.vo.RagFileVO;
import com.lion.agent.ai.rag.pojo.vo.RagParseProgressVO;
import com.lion.agent.ai.rag.pojo.vo.RagSplitterVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RAG 知识库接口(登录后可用, 位于 /api/** 自动纳入 Sa-Token 登录校验)
 * <p>
 * 上传与解析分两阶段(上传零模型成本, 解析由用户显式触发):
 * <ol>
 *   <li>上传文档(仅保存):       POST   /api/rag/upload (multipart, 支持 .pdf/.txt/.md, 上限 20MB)</li>
 *   <li>解析文档(异步触发):     POST   /api/rag/documents/{id}/parse (校验后立即返回, 进度经列表轮询)</li>
 *   <li>下载文档原文:           GET    /api/rag/documents/{id}/download (鉴权后流式回吐)</li>
 *   <li>知识库问答:             POST   /api/rag/chat  body={"question":"你的问题"}</li>
 *   <li>文档列表:               GET    /api/rag/documents (按上传时间倒序, 含解析状态)</li>
 *   <li>删除单个文档:           DELETE /api/rag/documents/{id} (同时删除该文件全部向量)</li>
 *   <li>查看片段数:             GET    /api/rag/size</li>
 *   <li>切分方式列表:           GET    /api/rag/splitters (解析时下拉框数据源)</li>
 * </ol>
 */
@Tag(name = "RAG 知识库", description = "文档上传/解析入库 / 知识库问答 / 文档管理(需登录)")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 上传知识文档(仅保存, 不解析不产生模型费用)
     * <p>
     * 切分方式在「解析」时指定: POST /api/rag/documents/{id}/parse。
     */
    @Operation(summary = "上传知识文档(仅保存)",
            description = "multipart 上传, 支持 .pdf/.txt/.md, 上限 20MB; 仅保存文件与元数据(状态=待解析), "
                    + "需再调用解析接口才会切分向量化入库")
    @PostMapping("/upload")
    public R<RagDocumentVO> upload(
            @Parameter(description = "知识文档文件(.pdf/.txt/.md)", required = true)
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        String name = file.getOriginalFilename();
        if (!RagUtils.isSupported(name)) {
            throw new BusinessException("仅支持 .txt / .md / .pdf 格式的知识文档");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException("读取上传文件失败: " + RagUtils.rootMessage(e));
        }
        return R.ok(ragService.uploadDocument(name, file.getSize(), data));
    }

    /**
     * 触发文档解析(异步, 立即返回; 对已解析文档重复调用即重新解析)
     * <p>
     * 切分方式可由前端指定(见 GET /api/rag/splitters): splitterType 选方式,
     * segmentSize / overlap / pattern 覆盖默认参数, 不传则用该方式的默认值。
     * 进度通过文档列表(GET /api/rag/documents)轮询 status/progress 获取。
     */
    @Operation(summary = "触发解析(异步)",
            description = "校验并置'解析中'后立即返回; 切分+向量化由后台线程池执行, "
                    + "进度/结果经文档列表的 status/progress/errorMsg 字段轮询获取; "
                    + "对已解析文档重复调用 = 重新解析(先清旧向量再按新配置重建); "
                    + "切分方式由 splitterType 等可选参数指定, 不传用默认值")
    @PostMapping("/documents/{id}/parse")
    public R<RagParseProgressVO> parse(
            @Parameter(description = "文档id", required = true, example = "1")
            @PathVariable("id") Long id,
            @Parameter(description = "切分方式, 见 GET /api/rag/splitters", example = "PARAGRAPH")
            @RequestParam(value = "splitterType", required = false) String splitterType,
            @Parameter(description = "片段大小(字符数)", example = "500")
            @RequestParam(value = "segmentSize", required = false) Integer segmentSize,
            @Parameter(description = "相邻片段重叠字符数", example = "80")
            @RequestParam(value = "overlap", required = false) Integer overlap,
            @Parameter(description = "自定义切分正则(仅 REGEX 方式需要)")
            @RequestParam(value = "pattern", required = false) String pattern) {
        return R.ok(ragService.parseDocument(id,
                SplitterConfig.of(splitterType, segmentSize, overlap, pattern)));
    }

    /**
     * 文档列表(文件列表页数据源)
     */
    @Operation(summary = "文档列表", description = "按上传时间倒序, 供前端文件列表展示")
    @GetMapping("/documents")
    public R<List<RagDocumentVO>> documents() {
        return R.ok(ragService.listDocuments());
    }

    /**
     * 删除单个文档(同时删除该文件在向量库中的全部片段)
     */
    @Operation(summary = "删除单个文档", description = "同时删除该文件在向量库中的全部片段")
    @DeleteMapping("/documents/{id}")
    public R<Void> deleteDocument(
            @Parameter(description = "文档id", required = true, example = "1")
            @PathVariable("id") Long id) {
        ragService.deleteDocument(id);
        return R.ok();
    }

    /**
     * 下载文档原文(管理页"查看原文"用): 走后端鉴权流式回吐, 不暴露对象存储地址
     */
    @Operation(summary = "下载文档原文", description = "按文档记录的存储方式(local/minio)读取原文并回吐; "
            + "bucket 为私有, 外部获取统一走本接口鉴权")
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(
            @Parameter(description = "文档id", required = true, example = "1")
            @PathVariable("id") Long id) {
        RagFileVO file = ragService.downloadDocument(id);
        // RFC 5987 编码文件名(中文/空格安全); PDF 标记二进制类型, 文本类按 UTF-8 文本
        String encodedName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        MediaType mediaType = "pdf".equalsIgnoreCase(file.getFileType())
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("text/plain;charset=UTF-8");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentLength(file.getData().length)
                .body(file.getData());
    }

    /**
     * 知识库问答(阻塞式, 整段返回 AI 回答 + 引用来源)
     */
    @Operation(summary = "知识库问答", description = "阻塞式, 先向量检索相关知识片段再由千问生成回答; "
            + "返回回答文本与引用来源列表(检索层注入, 含文档名/匹配度/片段原文)")
    @PostMapping("/chat")
    public R<RagAnswerVO> chat(@RequestBody @Valid RagChatRequest request) {
        return R.ok(ragService.answer(request.getQuestion()));
    }

    /**
     * 当前知识库片段数
     */
    @Operation(summary = "当前知识库片段数", description = "向量库中所有知识片段的总数")
    @GetMapping("/size")
    public R<Long> size() {
        return R.ok(ragService.size());
    }

    /**
     * 可选的切分方式列表(上传时下拉框数据源, 含默认参数与正则填写提示)
     */
    @Operation(summary = "切分方式列表", description = "上传文档时下拉框数据源, 含默认参数与正则填写提示")
    @GetMapping("/splitters")
    public R<List<RagSplitterVO>> splitters() {
        return R.ok(ragService.listSplitters());
    }
}

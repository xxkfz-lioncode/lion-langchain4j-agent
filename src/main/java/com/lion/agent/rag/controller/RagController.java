package com.lion.agent.rag.controller;

import com.lion.agent.common.Result;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.rag.dto.RagChatRequest;
import com.lion.agent.rag.service.RagService;
import com.lion.agent.rag.splitter.SplitterConfig;
import com.lion.agent.rag.vo.RagDocumentVO;
import com.lion.agent.rag.vo.RagSplitterVO;
import com.lion.agent.rag.vo.RagUploadVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * RAG 知识库接口(登录后可用, 位于 /api/** 自动纳入 Sa-Token 登录校验)
 * <p>
 * 1. 上传知识文档:    POST /api/rag/upload (multipart, 支持 .pdf/.txt/.md, 上限 20MB, 追加式入库)
 * 2. 知识库问答:      POST /api/rag/chat  body={"question":"你的问题"}
 * 3. 文档列表:        GET  /api/rag/documents (按上传时间倒序, 供前端文件列表展示)
 * 4. 删除单个文档:    DELETE /api/rag/documents/{id} (同时删除该文件全部向量)
 * 5. 查看片段数:      GET  /api/rag/size
 * 6. 切分方式列表:    GET  /api/rag/splitters (前端上传时下拉框数据源)
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 上传知识文档并追加入库(多个文档共存, 不替换旧知识)
     * <p>
     * 切分方式可由前端指定(见 GET /api/rag/splitters): splitterType 选方式,
     * segmentSize / overlap / pattern 覆盖默认参数, 不传则用该方式的默认值。
     */
    @PostMapping("/upload")
    public Result<RagUploadVO> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "splitterType", required = false) String splitterType,
                                      @RequestParam(value = "segmentSize", required = false) Integer segmentSize,
                                      @RequestParam(value = "overlap", required = false) Integer overlap,
                                      @RequestParam(value = "pattern", required = false) String pattern)
            throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        String name = file.getOriginalFilename();
        String lower = (name == null ? "" : name).toLowerCase();
        boolean supported = lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".pdf");
        if (!supported) {
            throw new BusinessException("仅支持 .txt / .md / .pdf 格式的知识文档");
        }
        return Result.ok(ragService.ingestUpload(name, file.getSize(), file.getBytes(),
                SplitterConfig.of(splitterType, segmentSize, overlap, pattern)));
    }

    /**
     * 文档列表(文件列表页数据源)
     */
    @GetMapping("/documents")
    public Result<List<RagDocumentVO>> documents() {
        return Result.ok(ragService.listDocuments());
    }

    /**
     * 删除单个文档(同时删除该文件在向量库中的全部片段)
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable("id") Long id) {
        ragService.deleteDocument(id);
        return Result.ok();
    }

    /**
     * 知识库问答(阻塞式, 整段返回 AI 回答)
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody @Valid RagChatRequest request) {
        return Result.ok(ragService.answer(request.getQuestion()));
    }

    /**
     * 当前知识库片段数
     */
    @GetMapping("/size")
    public Result<Long> size() {
        return Result.ok(ragService.size());
    }

    /**
     * 可选的切分方式列表(上传时下拉框数据源, 含默认参数与正则填写提示)
     */
    @GetMapping("/splitters")
    public Result<List<RagSplitterVO>> splitters() {
        return Result.ok(ragService.listSplitters());
    }
}

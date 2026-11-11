package com.lion.agent.ai.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.common.exception.BusinessException;

import com.lion.agent.ai.rag.pojo.entity.RagDocumentEntity;
import com.lion.agent.ai.rag.mapper.RagDocumentMapper;
import com.lion.agent.ai.rag.service.RagService;
import com.lion.agent.ai.rag.splitter.DocumentSplitterFactory;
import com.lion.agent.ai.rag.splitter.ResolvedSplitter;
import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.splitter.SplitterType;
import com.lion.agent.ai.rag.pojo.vo.RagDocumentVO;
import com.lion.agent.ai.rag.pojo.vo.RagSplitterVO;
import com.lion.agent.ai.rag.pojo.vo.RagUploadVO;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RAG 知识库服务实现
 * <p>
 * 链路(上传与解析解耦, 上传阶段零模型调用):
 * 1. 上传: 仅保存原始文件(upload 目录) + 落 rag_document(status=待解析), 不产生任何模型费用;
 * 2. 解析(用户显式触发): 加载已存文件({@link FileSystemDocumentLoader}, PDF 用 ApachePdfBoxDocumentParser)
 *    → 切分(支持多种方式, 见 {@link SplitterType}, 参数由 {@link SplitterConfig} 传入,
 *    经 {@link DocumentSplitterFactory} 校验后构建切分器, 默认递归 300/50)
 *    → 分批(≤10 条/批, DashScope text-embedding-v3 单请求上限)调 {@link EmbeddingModel#embedAll}
 *    → 写入向量库(避开 EmbeddingStoreIngestor 整篇一次性 embedAll 的 batch size 限制);
 *    已解析文档可重新解析(先清旧向量再按新配置重建);
 * 3. 问答: {@link EmbeddingStoreContentRetriever} 检索 Top-N 相关片段(低于 minScore 过滤) → 拼入提示词 → 千问作答。
 * <p>
 * 说明:
 * - 向量化模型 = DashScope text-embedding-v3(由 application.yml embedding-model 配置自动装配);
 * - 向量库 = MilvusEmbeddingStore(langchain4j-milvus-spring-boot-starter 自动装配, 需 Milvus 2.4+,
 *   见 application.yml 的 langchain4j.milvus.* 与 {@code rag/config/RagVectorStoreConfig});
 * - 入库采用"追加式": 多个文档向量共存, 每个片段携带 docId/fileName 元数据(随片段存入 Milvus 的
 *   metadata JSON 字段), 支持文档列表持久化(rag_document)与按文件删除(removeAll(Filter));
 * - 上传的原始文件保存到 upload 目录(可配 lion.rag.upload-dir, 文件名加 docId 前缀防重名),
 *   路径记录在 rag_document.file_path, 删除文档时一并删除。
 */
@Slf4j
@Service
public class RagServiceImpl implements RagService {

    /** 检索最多返回的相似片段数 */
    private static final int MAX_RESULTS = 5;
    /** 相似度低于该值的片段将被过滤 */
    private static final double MIN_SCORE = 0.5;
    /** 向量化单批最大条数(DashScope text-embedding-v3 限制: 单请求最多 10 条 input) */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /** 向量片段元数据: 文档 id(即 rag_document.id, 删除/过滤用) */
    public static final String META_DOC_ID = "docId";
    /** 向量片段元数据: 文件名(展示/溯源用) */
    public static final String META_FILE_NAME = "fileName";

    private final ChatModel chatModel;
    /** 切分器工厂: 按请求参数(方式/块大小/重叠/正则)构建切分器, 支持前端自定义切分 */
    private final DocumentSplitterFactory splitterFactory;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingStoreContentRetriever retriever;
    private final RagDocumentMapper ragDocumentMapper;
    /** 上传文件保存目录(相对运行目录, 见 application.yml 的 lion.rag.upload-dir) */
    private final String uploadDir;

    public RagServiceImpl(ChatModel chatModel, EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          DocumentSplitterFactory splitterFactory,
                          RagDocumentMapper ragDocumentMapper,
                          @Value("${lion.rag.upload-dir:upload}") String uploadDir) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        // 向量库由 milvus starter 自动装配(MilvusEmbeddingStore), 经 rag/config/RagVectorStoreConfig
        // 的 @Primary Bean 锁定, 不要在此处手工 new, 否则与注入/检索用的 store 不一致
        this.embeddingStore = embeddingStore;
        this.splitterFactory = splitterFactory;
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .build();
        this.ragDocumentMapper = ragDocumentMapper;
        this.uploadDir = uploadDir;
    }

    @Override
    public RagDocumentVO uploadDocument(String fileName, long fileSize, byte[] data) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        if (data == null || data.length == 0) {
            throw new BusinessException("文件内容为空");
        }
        String type = fileTypeOf(fileName);

        // 1. 落 rag_document 元数据: 状态=待解析(0), 片段数 0, 切分方式留空(解析时才指定)
        RagDocumentEntity entity = new RagDocumentEntity();
        entity.setFileName(fileName);
        entity.setFileSize(fileSize);
        entity.setFileType(type);
        entity.setSegmentCount(0);
        entity.setStatus(0);
        ragDocumentMapper.insert(entity);
        Long docId = entity.getId();

        try {
            // 2. 原始文件保存到 upload 目录(解析阶段直接读取该文件)
            String storedPath = saveUploadFile(fileName, data, docId);
            entity.setFilePath(storedPath);
            ragDocumentMapper.updateById(entity);
            log.info("文档[{}]已上传(仅保存, 待解析): id={}, 保存至 {}", fileName, docId, storedPath);
            return toVO(entity);
        } catch (Exception e) {
            // 失败回滚: 删元数据记录 + 删已保存文件(此阶段无向量, 无需清理)
            ragDocumentMapper.deleteById(docId);
            throw new BusinessException("文件上传失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    @Override
    public RagUploadVO parseDocument(Long id, SplitterConfig splitConfig) {
        if (id == null) {
            throw new BusinessException("文档 id 不能为空");
        }
        RagDocumentEntity entity = ragDocumentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("文档不存在: " + id);
        }
        if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
            throw new BusinessException("文档缺少已上传的原始文件, 请删除后重新上传");
        }

        // 0. 先解析切分参数(非法参数直接报错, 不动库不动向量)
        ResolvedSplitter resolved = splitterFactory.resolve(splitConfig);

        boolean isReParse = entity.getStatus() != null && entity.getStatus() == 1;
        if (isReParse) {
            // 重新解析: 先清空该文档全部旧向量, 再按新配置重建
            embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(String.valueOf(id)));
            log.info("文档[{}]重新解析: 已清空旧向量 {} 个", entity.getFileName(), entity.getSegmentCount());
        }

        try {
            // 1. 读取已上传的原始文件(上传阶段已落盘, 此处不再接收文件流)
            Path stored = Path.of(entity.getFilePath());
            if (!stored.isAbsolute()) {
                stored = Path.of("").toAbsolutePath().resolve(stored);
            }
            if (!Files.exists(stored)) {
                throw new BusinessException("原始文件已丢失(" + entity.getFilePath() + "), 请删除后重新上传");
            }
            byte[] data = Files.readAllBytes(stored);

            // 2. 解析文档内容(pdf 走 ApachePdfBoxDocumentParser, 文本类 UTF-8 读取)
            Document document = parse(entity.getFilePath(), entity.getFileType(), data);

            // 3. 按所选方式切分(滤掉空片段) → 每段挂 docId/fileName 元数据 → 分批向量化入库
            List<TextSegment> segments = resolved.splitter().split(document).stream()
                    .filter(segment -> segment.text() != null && !segment.text().isBlank())
                    .toList();
            if (!segments.isEmpty()) {
                segments = attachMetadata(segments, id, entity.getFileName());
                for (int i = 0; i < segments.size(); i += EMBEDDING_BATCH_SIZE) {
                    List<TextSegment> batch =
                            segments.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, segments.size()));
                    List<Embedding> embeddings = embedBatch(batch);
                    embeddingStore.addAll(embeddings, batch);
                    log.info("文档[{}]向量化入库中: {}/{} 片段", entity.getFileName(),
                            Math.min(i + EMBEDDING_BATCH_SIZE, segments.size()), segments.size());
                }
            }

            // 4. 回填状态=已解析 / 片段数 / 本次切分方案
            entity.setStatus(1);
            entity.setSegmentCount(segments.size());
            entity.setSplitterType(resolved.type().getCode());
            entity.setSegmentSize(resolved.segmentSize());
            entity.setOverlapSize(resolved.overlap());
            entity.setSplitterPattern(resolved.pattern() == null ? "" : resolved.pattern());
            ragDocumentMapper.updateById(entity);
            log.info("文档[{}]解析完成: {}, 入库 {} 个片段, 当前知识库共 {} 片段",
                    entity.getFileName(), resolved.display(), segments.size(), size());

            RagUploadVO vo = new RagUploadVO();
            vo.setId(id);
            vo.setFileName(entity.getFileName());
            vo.setFileSize(entity.getFileSize());
            vo.setFileType(entity.getFileType());
            vo.setFilePath(entity.getFilePath());
            vo.setSegments(segments.size());
            vo.setTotal(size());
            vo.setSplitterType(resolved.type().getCode());
            vo.setSegmentSize(resolved.segmentSize());
            vo.setOverlapSize(resolved.overlap());
            return vo;
        } catch (Exception e) {
            // 解析失败: 清理本次已写入的部分向量, 文档保持"待解析"可重试(记录与文件都保留)
            try {
                embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(String.valueOf(id)));
            } catch (Exception cleanup) {
                log.warn("清理文档[{}]残留向量失败: {}", entity.getFileName(), cleanup.getMessage());
            }
            entity.setStatus(0);
            entity.setSegmentCount(0);
            ragDocumentMapper.updateById(entity);
            throw new BusinessException("文档解析失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 实体 → 列表 VO(字段一一对应) */
    private RagDocumentVO toVO(RagDocumentEntity e) {
        RagDocumentVO vo = new RagDocumentVO();
        vo.setId(e.getId());
        vo.setFileName(e.getFileName());
        vo.setFileSize(e.getFileSize());
        vo.setFileType(e.getFileType());
        vo.setFilePath(e.getFilePath());
        vo.setSegmentCount(e.getSegmentCount());
        vo.setStatus(e.getStatus());
        vo.setSplitterType(e.getSplitterType());
        vo.setSegmentSize(e.getSegmentSize());
        vo.setOverlapSize(e.getOverlapSize());
        vo.setSplitterPattern(e.getSplitterPattern());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    @Override
    public List<RagDocumentVO> listDocuments() {
        List<RagDocumentEntity> entities = ragDocumentMapper.selectList(
                new LambdaQueryWrapper<RagDocumentEntity>()
                        .orderByDesc(RagDocumentEntity::getCreateTime)
                        .orderByDesc(RagDocumentEntity::getId));
        List<RagDocumentVO> result = new ArrayList<>(entities.size());
        for (RagDocumentEntity e : entities) {
            result.add(toVO(e));
        }
        return result;
    }

    @Override
    public List<RagSplitterVO> listSplitters() {
        // 下拉框数据源由 SplitterType 枚举生成: 枚举新增一项, 前端无需改动即可展示
        List<RagSplitterVO> options = new ArrayList<>(SplitterType.values().length);
        for (SplitterType type : SplitterType.values()) {
            RagSplitterVO vo = new RagSplitterVO();
            vo.setCode(type.getCode());
            vo.setLabel(type.getLabel());
            vo.setShortLabel(type.getShortLabel());
            vo.setDescription(type.getDescription());
            vo.setDefaultSegmentSize(type.getDefaultSegmentSize());
            vo.setDefaultOverlap(type.getDefaultOverlap());
            vo.setPatternSupported(type.isPatternSupported());
            vo.setDefaultPattern(type.getDefaultPattern());
            vo.setPatternHint(type.getPatternHint());
            vo.setMinSegmentSize(DocumentSplitterFactory.MIN_SEGMENT_SIZE);
            vo.setMaxSegmentSize(DocumentSplitterFactory.MAX_SEGMENT_SIZE);
            options.add(vo);
        }
        return options;
    }

    @Override
    public void deleteDocument(Long id) {
        if (id == null) {
            throw new BusinessException("文档 id 不能为空");
        }
        RagDocumentEntity entity = ragDocumentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("文档不存在: " + id);
        }
        // 1. 先删该文档的全部向量(按 docId 元数据过滤, Milvus metadata JSON 字段支撑)
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID)
                .isEqualTo(String.valueOf(id)));

        // 2. 再删元数据记录
        ragDocumentMapper.deleteById(id);
        // 3. 最后删 upload 目录中的原始文件(失败仅告警)
        deleteUploadFile(entity.getFilePath());
        log.info("文档[{}]已删除(含 {} 个向量片段, 文件: {})",
                entity.getFileName(), entity.getSegmentCount(), entity.getFilePath());
    }

    /**
     * 按文件名取扩展名作为类型(pdf/txt/md/markdown → md)
     */
    private String fileTypeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        if (lower.endsWith(".markdown")) {
            return "md";
        }
        if (lower.endsWith(".md")) {
            return "md";
        }
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot + 1) : "";
    }

    /**
     * 解析文件内容: pdf 直接用已保存的上传文件交给 ApachePdfBoxDocumentParser(省一次临时落盘),
     * 其余(txt/md 等)按 UTF-8 读取字节内容
     */
    private Document parse(String storedPath, String type, byte[] data) throws Exception {
        if ("pdf".equals(type)) {
            return FileSystemDocumentLoader.loadDocument(storedPath, new ApachePdfBoxDocumentParser());
        }
        return Document.from(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * 保存上传的原始文件到 upload 目录: 文件名加 docId 前缀防重名覆盖,
     * 返回相对运行目录的路径(存库供展示/删除), 如 upload/3_需求文档.pdf
     */
    private String saveUploadFile(String fileName, byte[] data, Long docId) throws IOException {
        Path dir = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(docId + "_" + sanitizeFileName(fileName)).normalize();
        // 防目录穿越: 归一化后必须仍落在 upload 目录内
        if (!target.startsWith(dir)) {
            throw new BusinessException("文件名非法: " + fileName);
        }
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return Path.of(uploadDir).resolve(target.getFileName()).toString().replace('\\', '/');
    }

    /** 去掉路径部分与非法字符(防目录穿越 / Windows 文件名非法字符导致落盘失败) */
    private String sanitizeFileName(String fileName) {
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    /** 删除 upload 目录中的上传文件(相对路径按运行目录解析); 失败仅告警, 不阻断主流程 */
    private void deleteUploadFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(filePath);
            if (!path.isAbsolute()) {
                path = Path.of("").toAbsolutePath().resolve(path);
            }
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("删除上传文件[{}]失败: {}", filePath, e.getMessage());
        }
    }

    /**
     * 为片段附加 docId/fileName 元数据(追加式入库时用于按文件区分/删除)
     */
    private List<TextSegment> attachMetadata(List<TextSegment> segments, Long docId, String fileName) {
        String docIdStr = String.valueOf(docId);
        return segments.stream()
                .map(segment -> {
                    Metadata meta = segment.metadata().copy()
                            .put(META_DOC_ID, docIdStr)
                            .put(META_FILE_NAME, fileName);
                    return TextSegment.from(segment.text(), meta);
                })
                .toList();
    }

    /** 批量向量化(失败自动重试 3 次, 应对 DashScope 网络抖动) */
    private List<Embedding> embedBatch(List<TextSegment> batch) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return embeddingModel.embedAll(batch).content();
            } catch (Exception e) {
                last = e;
                log.warn("向量化失败(第 {} 条批次, 第 {} 次尝试): {}", batch.size(), attempt, e.getMessage());
            }
        }
        throw new BusinessException("文档向量化失败: " + (last == null ? "未知错误" : last.getMessage()));
    }

    @Override
    public String answer(String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题内容不能为空");
        }
        // ContentRetriever 自动完成: 问题向量化 → 相似度检索 → 返回相关片段(minScore 已过滤低分)
        List<Content> contents = retriever.retrieve(Query.from(question));
        if (contents.isEmpty()) {
            return "知识库中暂时没有与您的问题相关的资料, 请先导入知识文档, 或换个问法试试。";
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            context.append(i + 1).append(". ").append(contents.get(i).textSegment().text()).append('\n');
        }

        String systemText = "你是知识库问答助手。请仅依据下面提供的参考资料回答用户的问题; "
                + "若资料中找不到答案, 请如实说明知识库中没有相关信息, 不要编造。\n\n参考资料:\n" + context;
        var response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(SystemMessage.from(systemText), UserMessage.from(question)))
                .build());
        return response.aiMessage().text();
    }

    @Override
    public long size() {
        return ragDocumentMapper.sumSegmentCount();
    }
}

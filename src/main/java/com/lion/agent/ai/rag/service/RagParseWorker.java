package com.lion.agent.ai.rag.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lion.agent.ai.rag.mapper.RagDocumentMapper;
import com.lion.agent.ai.rag.pojo.entity.RagDocumentEntity;
import com.lion.agent.ai.rag.service.impl.RagServiceImpl;
import com.lion.agent.ai.rag.splitter.DocumentSplitterFactory;
import com.lion.agent.ai.rag.splitter.ResolvedSplitter;
import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.storage.FileStorage;
import com.lion.agent.ai.rag.util.RagUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 文档解析异步执行器(@Async, 独立线程池, 不占 Web 请求线程)。
 * 执行"读文件 → 解析 → 切分 → 分批向量化"全过程, 进度/结果/失败原因实时回写
 * rag_document(progress/status/error_msg/parse_time), 前端轮询列表即可见。
 * 单独成 Bean 的原因: Spring 的 @Async 基于代理, 同类内部方法自调用不走代理线程;
 * 拆成 Controller → Service(同步校验+置状态) → Worker(异步执行) 后无自调用, 代理生效。
 */
@Slf4j
@Component
public class RagParseWorker {

    /** 向量化单批最大条数(DashScope text-embedding-v3 限制: 单请求最多 10 条 input) */
    private static final int EMBEDDING_BATCH_SIZE = 10;
    /** 失败原因落库截断长度(对应 rag_document.error_msg) */
    private static final int ERROR_MSG_MAX = 500;
    /** 进度占比: 切分完成记 10%, 其余随向量化批次推进到 95% */
    private static final int SPLIT_PROGRESS = 10;

    private final RagDocumentMapper ragDocumentMapper;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitterFactory splitterFactory;
    /** 全部存储实现(按 type() 索引), 解析时按记录的 storage_type 路由读取 */
    private final Map<String, FileStorage> storages;
    private final FileStorage activeStorage;

    public RagParseWorker(EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          DocumentSplitterFactory splitterFactory,
                          RagDocumentMapper ragDocumentMapper,
                          List<FileStorage> storageImpls,
                          @Value("${lion.rag.storage-type:local}") String activeStorageType) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.splitterFactory = splitterFactory;
        this.ragDocumentMapper = ragDocumentMapper;
        this.storages = storageImpls.stream()
                .collect(Collectors.toMap(FileStorage::type, Function.identity()));
        FileStorage active = storages.get(activeStorageType);
        if (active == null) {
            throw new IllegalArgumentException("未知的存储方式: " + activeStorageType);
        }
        this.activeStorage = active;
    }

    /** 按记录的 storage_type 取存储实现(空/未知回退激活存储) */
    private FileStorage storageOf(RagDocumentEntity entity) {
        FileStorage storage = entity.getStorageType() == null ? null : storages.get(entity.getStorageType());
        return storage != null ? storage : activeStorage;
    }
    /**
     * 执行解析任务(异步)。收尾只更新本任务相关列(LambdaUpdateWrapper),
     * 不做整体 updateById, 避免覆盖并发修改; docId 在触发阶段已校验合法性。
     */
    @Async("ragParseExecutor")
    public void executeParse(Long docId) {
        RagDocumentEntity entity = ragDocumentMapper.selectById(docId);
        if (entity == null) {
            log.warn("解析任务跳过: 文档 {} 不存在(可能已被删除)", docId);
            return;
        }
        log.info("文档[{}]异步解析开始: 切分方式 {}({}/{})", entity.getFileName(),
                entity.getSplitterType(), entity.getSegmentSize(), entity.getOverlapSize());
        try {
            // 0. 重建切分器(触发阶段已把切分方案持久化到记录上)
            ResolvedSplitter resolved = splitterFactory.resolve(SplitterConfig.of(
                    entity.getSplitterType(), entity.getSegmentSize(),
                    entity.getOverlapSize(), entity.getSplitterPattern()));

            // 1. 读取原始文件(按 storage_type 路由) → 解析文档内容
            //    PDF 按页拆成多个 Document(页码进元数据), 文本类为单个 Document
            byte[] data = storageOf(entity).load(entity.getFilePath());
            List<Document> documents = parseDocuments(entity.getFileType(), data);

            // 2. 逐个文档切分(滤空片段) → 挂 docId/fileName/页码 元数据 → 记 10% 进度
            List<TextSegment> segments = new ArrayList<>();
            for (Document document : documents) {
                resolved.splitter().split(document).stream()
                        .filter(segment -> segment.text() != null && !segment.text().isBlank())
                        .map(segment -> attachMetadata(segment, docId, entity.getFileName(), document.metadata()))
                        .forEach(segments::add);
            }
            updateProgress(docId, SPLIT_PROGRESS);

            // 3. 分批向量化入库, 按批次推进进度(10% → 95%)
            int total = segments.size();
            int done = 0;
            for (int i = 0; i < total; i += EMBEDDING_BATCH_SIZE) {
                List<TextSegment> batch =
                        segments.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, total));
                List<Embedding> embeddings = embedBatch(batch);
                embeddingStore.addAll(embeddings, batch);
                done += batch.size();
                updateProgress(docId, SPLIT_PROGRESS + (95 - SPLIT_PROGRESS) * done / Math.max(total, 1));
                log.info("文档[{}]向量化入库中: {}/{} 片段", entity.getFileName(), done, total);
            }

            // 4. 成功收尾: 状态/进度/片段数/完成时间
            ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                    .eq(RagDocumentEntity::getId, docId)
                    .set(RagDocumentEntity::getStatus, 1)
                    .set(RagDocumentEntity::getProgress, 100)
                    .set(RagDocumentEntity::getSegmentCount, total)
                    .set(RagDocumentEntity::getErrorMsg, "")
                    .set(RagDocumentEntity::getParseTime, LocalDateTime.now()));
            log.info("文档[{}]解析完成: {}, 入库 {} 个片段", entity.getFileName(), resolved.display(), total);
        } catch (Exception e) {
            // 失败收尾: 清理本次已写入的部分向量, 状态=失败 + 错误原因落库(可观测/可重试)
            log.error("文档[{}]解析失败: {}", entity.getFileName(), RagUtils.rootMessage(e), e);
            try {
                embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(RagServiceImpl.META_DOC_ID)
                        .isEqualTo(String.valueOf(docId)));
            } catch (Exception cleanup) {
                log.warn("清理文档[{}]残留向量失败: {}", entity.getFileName(), cleanup.getMessage());
            }
            ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                    .eq(RagDocumentEntity::getId, docId)
                    .set(RagDocumentEntity::getStatus, 3)
                    .set(RagDocumentEntity::getProgress, 0)
                    .set(RagDocumentEntity::getSegmentCount, 0)
                    .set(RagDocumentEntity::getErrorMsg,
                            RagUtils.snippet(RagUtils.rootMessage(e), ERROR_MSG_MAX)));
        }
    }

    /** 只更新解析进度列(轮询侧可见) */
    private void updateProgress(Long docId, int progress) {
        ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                .eq(RagDocumentEntity::getId, docId)
                .set(RagDocumentEntity::getProgress, progress));
    }

    /**
     * 解析文件内容 → Document 列表:
     * - pdf: 用 PDFBox 按页拆分(每页一个 Document, 页码写入元数据),
     *   这样切分出的片段能定位到"第 N 页"(引用溯源展示用);
     *   langchain4j 自带的 ApachePdfBoxDocumentParser 会把整本合成一个 Document, 页码信息丢失, 故弃用
     * - txt/md 等: 整个文件一个 Document(文本类无页概念)
     */
    private List<Document> parseDocuments(String type, byte[] data) throws Exception {
        if (!"pdf".equals(type)) {
            return List.of(Document.from(new String(data, StandardCharsets.UTF_8)));
        }
        List<Document> pages = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = pdf.getNumberOfPages();
            for (int p = 1; p <= pageCount; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(pdf);
                if (text == null || text.isBlank()) {
                    continue; // 空白页(纯图片页)跳过
                }
                Metadata meta = new Metadata().put(RagServiceImpl.META_PAGE, String.valueOf(p));
                pages.add(Document.from(text, meta));
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("PDF 没有可提取的文本内容(可能是纯图片扫描件, 当前不支持 OCR)");
        }
        return pages;
    }

    /**
     * 为片段附加元数据(追加式入库, 用于按文件区分/删除/引用展示):
     * docId/fileName 来自解析任务, 页码来自所属 PDF 页的文档元数据(txt/md 无页码)
     */
    private TextSegment attachMetadata(TextSegment segment, Long docId, String fileName, Metadata docMeta) {
        Metadata meta = segment.metadata().copy()
                .put(RagServiceImpl.META_DOC_ID, String.valueOf(docId))
                .put(RagServiceImpl.META_FILE_NAME, fileName);
        String page = docMeta == null ? null : docMeta.getString(RagServiceImpl.META_PAGE);
        if (page != null && !page.isBlank()) {
            meta.put(RagServiceImpl.META_PAGE, page);
        }
        return TextSegment.from(segment.text(), meta);
    }

    /** 批量向量化(失败自动重试 3 次, 应对 DashScope 网络抖动) */
    private List<Embedding> embedBatch(List<TextSegment> batch) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return embeddingModel.embedAll(batch).content();
            } catch (Exception e) {
                last = e;
                log.warn("向量化失败(第 {} 条批次, 第 {} 次尝试): {}", batch.size(), attempt, RagUtils.rootMessage(e));
            }
        }
        throw new IllegalStateException("文档向量化失败: " + RagUtils.rootMessage(last));
    }
}

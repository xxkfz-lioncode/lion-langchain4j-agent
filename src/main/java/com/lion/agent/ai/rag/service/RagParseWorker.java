package com.lion.agent.ai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lion.agent.ai.rag.mapper.RagDocumentMapper;
import com.lion.agent.ai.rag.mapper.RagImageMapper;
import com.lion.agent.ai.rag.mapper.RagSegmentMapper;
import com.lion.agent.ai.rag.parse.DocElement;
import com.lion.agent.ai.rag.parse.ElementParserRouter;
import com.lion.agent.ai.rag.parse.ElementType;
import com.lion.agent.ai.rag.parse.ParseContext;
import com.lion.agent.ai.rag.parse.StructureChunker;
import com.lion.agent.ai.rag.pojo.entity.RagDocumentEntity;
import com.lion.agent.ai.rag.pojo.entity.RagImageEntity;
import com.lion.agent.ai.rag.pojo.entity.RagSegmentEntity;
import com.lion.agent.ai.rag.service.impl.RagServiceImpl;
import com.lion.agent.ai.rag.storage.FileStorage;
import com.lion.agent.ai.rag.util.RagUtils;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 文档解析异步执行器(@Async, 独立线程池, 不占 Web 请求线程)。
 * <p>
 * 链路(结构化元素解析):
 * 读文件 → {@link ElementParserRouter} 按格式解析成元素流(标题/段落/表格/列表)
 * → {@link StructureChunker} 结构感知分块(章节路径/表格原子/段落聚合切)
 * → 分批向量化(≤10/批) → Milvus + rag_segment 同步落库。
 * 进度/结果/失败原因实时回写 rag_document, 前端轮询列表即可见。
 * <p>
 * 单独成 Bean 的原因: Spring 的 @Async 基于代理, 同类内部方法自调用不走代理线程。
 */
@Slf4j
@Component
public class RagParseWorker {

    /** 向量化单批最大条数(DashScope text-embedding-v3 限制: 单请求最多 10 条 input) */
    private static final int EMBEDDING_BATCH_SIZE = 10;
    /** 失败原因落库截断长度(对应 rag_document.error_msg) */
    private static final int ERROR_MSG_MAX = 500;
    /** 进度占比: 分块完成记 10%, 其余随向量化批次推进到 95% */
    private static final int SPLIT_PROGRESS = 10;

    private final RagDocumentMapper ragDocumentMapper;
    /** 片段表 Mapper: 解析成功后同步落库(混合检索 BM25 关键词路数据源) */
    private final RagSegmentMapper ragSegmentMapper;
    /** 图片表 Mapper: 提取的内嵌图片留底(rag_image) */
    private final RagImageMapper ragImageMapper;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    /** 元素解析路由: 按 fileType 选择格式解析器 */
    private final ElementParserRouter elementParserRouter;
    /** 结构感知分块器: 元素流 → 片段(章节路径/表格原子/段落聚合) */
    private final StructureChunker structureChunker;
    /** 全部存储实现(按 type() 索引), 解析时按记录的 storage_type 路由读取 */
    private final Map<String, FileStorage> storages;
    private final FileStorage activeStorage;

    public RagParseWorker(EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          ElementParserRouter elementParserRouter,
                          StructureChunker structureChunker,
                          RagDocumentMapper ragDocumentMapper,
                          RagSegmentMapper ragSegmentMapper,
                          RagImageMapper ragImageMapper,
                          List<FileStorage> storageImpls,
                          @Value("${lion.rag.storage-type:local}") String activeStorageType) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.elementParserRouter = elementParserRouter;
        this.structureChunker = structureChunker;
        this.ragDocumentMapper = ragDocumentMapper;
        this.ragSegmentMapper = ragSegmentMapper;
        this.ragImageMapper = ragImageMapper;
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
        log.info("文档[{}]异步解析开始: 类型 {}, 块大小 {}/重叠 {}", entity.getFileName(),
                entity.getFileType(), entity.getSegmentSize(), entity.getOverlapSize());
        try {
            // 1. 读取原始文件(按 storage_type 路由) → 元素流(标题/段落/表格/列表/图片)
            byte[] data = storageOf(entity).load(entity.getFilePath());
            // 重解析场景: 先清掉上一次提取的图片(行 + 存储对象), 与向量/片段"以最后成功一次为准"对齐
            deleteImages(docId, storageOf(entity));
            List<DocElement> elements = elementParserRouter.parse(ParseContext.builder()
                    .fileType(entity.getFileType())
                    .data(data)
                    .docId(docId)
                    .fileName(entity.getFileName())
                    .build());
            long storedImages = elements.stream()
                    .filter(e -> e.getType() == ElementType.IMAGE)
                    .count();
            if (storedImages > 0) {
                log.info("文档[{}]提取并存储 {} 张内嵌图片(rag_image 留底, 未向量化)", entity.getFileName(), storedImages);
            }

            // 2. 结构感知分块(段落聚合/表格原子/章节路径; 切分方式作用于段落二次切, 表格/图片豁免)
            //    → 挂 docId/fileName 元数据 → 记 10% 进度
            List<TextSegment> segments = structureChunker
                    .chunk(elements, entity.getSegmentSize(), entity.getOverlapSize(),
                            entity.getSplitterType(), entity.getSplitterPattern()).stream()
                    .filter(segment -> segment.text() != null && !segment.text().isBlank())
                    .map(segment -> attachMetadata(segment, docId, entity.getFileName()))
                    .collect(java.util.stream.Collectors.toList());
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

            // 4. 成功收尾: 片段同步落 rag_segment(BM25 关键词路数据源) + 图片落 rag_image 留底
            saveSegments(entity, segments);
            saveImages(entity, elements);
            ragDocumentMapper.update(null, new LambdaUpdateWrapper<RagDocumentEntity>()
                    .eq(RagDocumentEntity::getId, docId)
                    .set(RagDocumentEntity::getStatus, 1)
                    .set(RagDocumentEntity::getProgress, 100)
                    .set(RagDocumentEntity::getSegmentCount, total)
                    .set(RagDocumentEntity::getErrorMsg, "")
                    .set(RagDocumentEntity::getParseTime, LocalDateTime.now()));
            log.info("文档[{}]解析完成: 入库 {} 个片段(元素 {} 个)", entity.getFileName(), total, elements.size());
        } catch (Exception e) {
            // 失败收尾: 清理本次已写入的部分向量, 状态=失败 + 错误原因落库(可观测/可重试)
            log.error("文档[{}]解析失败: {}", entity.getFileName(), RagUtils.rootMessage(e), e);
            try {
                embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(RagServiceImpl.META_DOC_ID)
                        .isEqualTo(String.valueOf(docId)));
                deleteImages(docId, storageOf(entity));
            } catch (Exception cleanup) {
                log.warn("清理文档[{}]残留向量/图片失败: {}", entity.getFileName(), cleanup.getMessage());
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
     * 片段同步落 rag_segment(混合检索 BM25 关键词路数据源):
     * 重新解析时先删旧再插新(与向量"以最后成功一次为准"的语义一致);
     * 仅在向量化全部成功后调用, 失败路径不会污染片段表
     */
    private void saveSegments(RagDocumentEntity entity, List<TextSegment> segments) {
        ragSegmentMapper.delete(new LambdaQueryWrapper<RagSegmentEntity>()
                .eq(RagSegmentEntity::getDocId, entity.getId()));
        int seq = 0;
        for (TextSegment segment : segments) {
            RagSegmentEntity row = new RagSegmentEntity();
            row.setDocId(entity.getId());
            row.setSeq(seq++);
            String page = segment.metadata().getString(RagServiceImpl.META_PAGE);
            row.setPage(page == null || page.isBlank() ? null : Integer.valueOf(page));
            row.setElementType(segment.metadata().getString(RagServiceImpl.META_ELEMENT_TYPE));
            row.setSectionPath(segment.metadata().getString(RagServiceImpl.META_SECTION));
            // 表格片段行号(起止/总行数)由 StructureChunker 写入元数据, 同步落表供片段抽屉展示
            row.setRowStart(metaInt(segment, RagServiceImpl.META_ROW_START));
            row.setRowEnd(metaInt(segment, RagServiceImpl.META_ROW_END));
            row.setRowTotal(metaInt(segment, RagServiceImpl.META_ROW_TOTAL));
            row.setContent(segment.text());
            ragSegmentMapper.insert(row);
        }
        log.info("文档[{}]片段表落库完成: {} 条", entity.getFileName(), seq);
    }

    /** 元数据整数取值(不存在/空 → null) */
    private Integer metaInt(TextSegment segment, String key) {
        String v = segment.metadata().getString(key);
        return v == null || v.isBlank() ? null : Integer.valueOf(v);
    }

    /**
     * 图片留底: 解析产出的 IMAGE 元素 → rag_image(caption 预留 VLM 看图说话)。
     * 仅在向量化全部成功后调用(与 saveSegments 同一成功语义); 图片对象本身在解析时已落盘。
     */
    private void saveImages(RagDocumentEntity entity, List<DocElement> elements) {
        int count = 0;
        for (DocElement el : elements) {
            if (el.getType() != ElementType.IMAGE || el.getRef() == null) {
                continue;
            }
            RagImageEntity row = new RagImageEntity();
            row.setDocId(entity.getId());
            row.setPage(el.getPage());
            row.setObjectKey(el.getRef());
            row.setFileName(el.getText());
            row.setMime(el.getImageMime());
            row.setWidth(el.getImageWidth());
            row.setHeight(el.getImageHeight());
            ragImageMapper.insert(row);
            count++;
        }
        if (count > 0) {
            log.info("文档[{}]图片表落库完成: {} 张", entity.getFileName(), count);
        }
    }

    /** 清理该文档已提取的图片: 删 rag_image 行 + FileStorage 对象(重解析/失败重试前调用) */
    private void deleteImages(Long docId, FileStorage storage) {
        List<RagImageEntity> images = ragImageMapper.selectList(
                new LambdaQueryWrapper<RagImageEntity>().eq(RagImageEntity::getDocId, docId));
        for (RagImageEntity img : images) {
            storage.delete(img.getObjectKey());
        }
        if (!images.isEmpty()) {
            ragImageMapper.delete(new LambdaQueryWrapper<RagImageEntity>()
                    .eq(RagImageEntity::getDocId, docId));
            log.info("文档 {} 旧图片已清理: {} 张", docId, images.size());
        }
    }

    /**
     * 为片段补充任务级元数据(docId/fileName):
     * elementType/section/page 已由 StructureChunker 写入片段元数据, 此处追加式合并
     */
    private TextSegment attachMetadata(TextSegment segment, Long docId, String fileName) {
        Metadata meta = segment.metadata().copy()
                .put(RagServiceImpl.META_DOC_ID, String.valueOf(docId))
                .put(RagServiceImpl.META_FILE_NAME, fileName);
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

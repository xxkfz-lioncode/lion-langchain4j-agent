package com.lion.agent.ai.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.ai.rag.pojo.vo.*;
import com.lion.agent.common.exception.BusinessException;

import com.lion.agent.ai.rag.pojo.entity.RagDocumentEntity;
import com.lion.agent.ai.rag.mapper.RagDocumentMapper;
import com.lion.agent.ai.rag.service.RagParseWorker;
import com.lion.agent.ai.rag.service.RagService;
import com.lion.agent.ai.rag.splitter.DocumentSplitterFactory;
import com.lion.agent.ai.rag.splitter.ResolvedSplitter;
import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.splitter.SplitterType;
import com.lion.agent.ai.rag.storage.FileStorage;
import com.lion.agent.ai.rag.util.RagUtils;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 知识库服务实现
 * <p>
 * 链路(上传与解析解耦, 上传阶段零模型调用):
 * 1. 上传: 原始文件存入激活存储({@link FileStorage}, local/minio 由 lion.rag.storage-type 决定)
 *    + 落 rag_document(status=待解析, storage_type 随之记录), 不产生任何模型费用;
 * 2. 解析(用户显式触发): 按记录的 storage_type 从对应存储读取原始文件(PDF 用 ApachePdfBoxDocumentParser 流式解析)
 *    → 切分(支持多种方式, 见 {@link SplitterType}, 参数由 {@link SplitterConfig} 传入,
 *    经 {@link DocumentSplitterFactory} 校验后构建切分器, 默认递归 300/50)
 *    → 分批(≤10 条/批, DashScope text-embedding-v3 单请求上限)调 {@link EmbeddingModel#embedAll}
 *    → 写入向量库(避开 EmbeddingStoreIngestor 整篇一次性 embedAll 的 batch size 限制);
 *    已解析文档可重新解析(先清旧向量再按新配置重建);
 * 3. 问答: 直接检索向量库(带相似度分数)取 Top-N 片段(低于 minScore 过滤) → 拼入提示词 → 千问作答,
 *    片段随回答作为引用来源一并返回(文档名/匹配度/原文)。
 * <p>
 * 说明:
 * - 向量化模型 = DashScope text-embedding-v3(由 application.yml embedding-model 配置自动装配);
 * - 向量库 = MilvusEmbeddingStore(langchain4j-milvus-spring-boot-starter 自动装配, 需 Milvus 2.4+,
 *   见 application.yml 的 langchain4j.milvus.* 与 {@code rag/config/RagVectorStoreConfig});
 * - 入库采用"追加式": 多个文档向量共存, 每个片段携带 docId/fileName 元数据(随片段存入 Milvus 的
 *   metadata JSON 字段), 支持文档列表持久化(rag_document)与按文件删除(removeAll(Filter));
 * - 原始文件经 {@link FileStorage} 抽象存储: local(upload 目录, lion.rag.upload-dir)或
 *   MinIO(lion.minio.*), 对象Key记录在 rag_document.file_path, 存储方式记录在 storage_type,
 *   删除/解析/下载均按 storage_type 路由, 两种存储可共存(local 存量数据无需迁移即可继续解析)。
 */
@Slf4j
@Service
public class RagServiceImpl implements RagService {

    /** 检索最多返回的相似片段数 */
    private static final int MAX_RESULTS = 2;
    /** 相似度低于该值的片段将被过滤 */
    private static final double MIN_SCORE = 0.75;
    /** 引用来源摘要截断长度(超出部分以 … 结尾, 全文走 text 字段) */
    private static final int SNIPPET_MAX = 120;

    /** 向量片段元数据: 文档 id(即 rag_document.id, 删除/过滤用) */
    public static final String META_DOC_ID = "docId";
    /** 向量片段元数据: 文件名(展示/溯源用) */
    public static final String META_FILE_NAME = "fileName";
    /** 向量片段元数据: PDF 页码(引用溯源"第 N 页"展示用; txt/md 无页码) */
    public static final String META_PAGE = "page";

    private final ChatModel chatModel;
    /** 切分器工厂: 按请求参数(方式/块大小/重叠/正则)构建切分器, 支持前端自定义切分 */
    private final DocumentSplitterFactory splitterFactory;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagDocumentMapper ragDocumentMapper;
    /** 全部存储实现(local + minio), 按各自 type() 索引; 存量文件按 rag_document.storage_type 路由 */
    private final Map<String, FileStorage> storages;
    /** 激活存储(lion.rag.storage-type): 新上传文件落这里 */
    private final FileStorage activeStorage;
    /** 异步解析执行器: 校验/置状态后把耗时任务交给它(独立线程池, 不占 HTTP 线程) */
    private final RagParseWorker parseWorker;

    public RagServiceImpl(ChatModel chatModel, EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          DocumentSplitterFactory splitterFactory,
                          RagDocumentMapper ragDocumentMapper,
                          List<FileStorage> storageImpls,
                          RagParseWorker parseWorker,
                          @Value("${lion.rag.storage-type:local}") String activeStorageType) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        // 向量库由 milvus starter 自动装配(MilvusEmbeddingStore), 经 rag/config/RagVectorStoreConfig
        // 的 @Primary Bean 锁定, 不要在此处手工 new, 否则与注入/检索用的 store 不一致
        this.embeddingStore = embeddingStore;
        this.splitterFactory = splitterFactory;
        this.ragDocumentMapper = ragDocumentMapper;
        this.parseWorker = parseWorker;
        this.storages = storageImpls.stream()
                .collect(Collectors.toMap(FileStorage::type, Function.identity()));
        FileStorage active = storages.get(activeStorageType);
        if (active == null) {
            throw new IllegalArgumentException("未知的存储方式: " + activeStorageType + " (可选: " + storages.keySet() + ")");
        }
        this.activeStorage = active;
    }

    /** 按记录的 storage_type 取存储实现(空/未知回退激活存储, 兼容历史数据) */
    private FileStorage storageOf(RagDocumentEntity entity) {
        String type = entity.getStorageType();
        FileStorage storage = type == null ? null : storages.get(type);
        return storage != null ? storage : activeStorage;
    }

    @Override
    public RagDocumentVO uploadDocument(String fileName, long fileSize, byte[] data) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        if (data == null || data.length == 0) {
            throw new BusinessException("文件内容为空");
        }
        String type = RagUtils.typeOf(fileName);

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
            // 2. 原始文件存入激活存储(local/minio 由 lion.rag.storage-type 决定)
            String objectKey = activeStorage.save(docId, fileName, data);
            entity.setFilePath(objectKey);
            entity.setStorageType(activeStorage.type());
            ragDocumentMapper.updateById(entity);
            log.info("文档[{}]已上传(仅保存, 待解析): id={}, 存储={}, 对象={}",
                    fileName, docId, activeStorage.type(), objectKey);
            return toVO(entity);
        } catch (Exception e) {
            // 失败回滚: 删元数据记录 + 删已保存文件(此阶段无向量, 无需清理)
            ragDocumentMapper.deleteById(docId);
            throw new BusinessException("文件上传失败: " + RagUtils.rootMessage(e));
        }
    }

    @Override
    public RagParseProgressVO parseDocument(Long id, SplitterConfig splitConfig) {
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
        if (entity.getStatus() != null && entity.getStatus() == 2) {
            throw new BusinessException("文档正在解析中, 请等待完成后再操作");
        }

        // 0. 先解析切分参数(非法参数直接报错, 不动库不动向量)
        ResolvedSplitter resolved = splitterFactory.resolve(splitConfig);

        // 1. 重新解析: 先清空该文档全部旧向量(失败可重试, 片段以最后成功的一次为准)
        boolean isReParse = entity.getStatus() != null && entity.getStatus() == 1;
        if (isReParse) {
            embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(String.valueOf(id)));
            log.info("文档[{}]重新解析: 已清空旧向量 {} 个", entity.getFileName(), entity.getSegmentCount());
        }

        // 2. 置"解析中"并把切分方案持久化到记录上(异步任务据此重建切分器), 触发即返回
        entity.setStatus(2);
        entity.setProgress(0);
        entity.setErrorMsg("");
        entity.setRetryCount((entity.getRetryCount() == null ? 0 : entity.getRetryCount()) + 1);
        entity.setSplitterType(resolved.type().getCode());
        entity.setSegmentSize(resolved.segmentSize());
        entity.setOverlapSize(resolved.overlap());
        entity.setSplitterPattern(resolved.pattern() == null ? "" : resolved.pattern());
        entity.setParseTime(null);
        ragDocumentMapper.updateById(entity);

        // 3. 交给独立线程池异步执行(读文件/切分/向量化为耗时任务, 不占 HTTP 线程)
        parseWorker.executeParse(id);
        log.info("文档[{}]解析任务已提交(异步执行)", entity.getFileName());
        return toProgressVO(entity);
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
        vo.setProgress(e.getProgress());
        vo.setErrorMsg(e.getErrorMsg());
        vo.setRetryCount(e.getRetryCount());
        vo.setParseTime(e.getParseTime());
        vo.setSplitterType(e.getSplitterType());
        vo.setSegmentSize(e.getSegmentSize());
        vo.setOverlapSize(e.getOverlapSize());
        vo.setSplitterPattern(e.getSplitterPattern());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    /** 实体 → 解析进度 VO(触发接口返回值) */
    private RagParseProgressVO toProgressVO(RagDocumentEntity e) {
        RagParseProgressVO vo = new RagParseProgressVO();
        vo.setId(e.getId());
        vo.setFileName(e.getFileName());
        vo.setStatus(e.getStatus());
        vo.setProgress(e.getProgress());
        vo.setErrorMsg(e.getErrorMsg());
        vo.setRetryCount(e.getRetryCount());
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
        if (entity.getStatus() != null && entity.getStatus() == 2) {
            // 解析中的文档删除会导致异步任务回写失败/残留向量, 直接拒绝
            throw new BusinessException("文档正在解析中, 请等待解析完成后再删除");
        }
        // 1. 先删该文档的全部向量(按 docId 元数据过滤, Milvus metadata JSON 字段支撑)
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID)
                .isEqualTo(String.valueOf(id)));

        // 2. 再删元数据记录
        ragDocumentMapper.deleteById(id);
        // 3. 最后删原始文件(按记录的 storage_type 路由; 失败仅告警, 不阻断主流程)
        storageOf(entity).delete(entity.getFilePath());
        log.info("文档[{}]已删除(含 {} 个向量片段, 存储={}, 对象={})",
                entity.getFileName(), entity.getSegmentCount(), entity.getStorageType(), entity.getFilePath());
    }

    @Override
    public RagFileVO downloadDocument(Long id) {
        if (id == null) {
            throw new BusinessException("文档 id 不能为空");
        }
        RagDocumentEntity entity = ragDocumentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("文档不存在: " + id);
        }
        FileStorage storage = storageOf(entity);
        byte[] data = storage.load(entity.getFilePath());
        RagFileVO vo = new RagFileVO();
        vo.setFileName(entity.getFileName());
        vo.setFileType(entity.getFileType());
        vo.setData(data);
        return vo;
    }

    @Override
    public RagAnswerVO answer(String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题内容不能为空");
        }
        // 直接检索向量库(不用 ContentRetriever, 因为需要每个片段的相似度分数作引用匹配度):
        // 问题向量化 → 相似度检索 Top-N(minScore 已过滤低分), 结果按分数倒序
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .build()).matches();

        // 引用来源由检索层注入(与拼进提示词的片段一一对应), 不依赖 LLM 自述出处, 保证真实可查
        List<RagSourceVO> sources = matches.stream().map(this::toSource).toList();
        if (matches.isEmpty()) {
            RagAnswerVO empty = new RagAnswerVO();
            empty.setAnswer("知识库中暂时没有与您的问题相关的资料, 请先导入知识文档, 或换个问法试试。");
            empty.setSources(List.of());
            return empty;
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            context.append(i + 1).append(". ").append(matches.get(i).embedded().text()).append('\n');
        }

        String systemText = "你是知识库问答助手。请仅依据下面提供的参考资料回答用户的问题; "
                + "若资料中找不到答案, 请如实说明知识库中没有相关信息, 不要编造。\n\n参考资料:\n" + context;
        var response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(SystemMessage.from(systemText), UserMessage.from(question)))
                .build());

        RagAnswerVO vo = new RagAnswerVO();
        vo.setAnswer(response.aiMessage().text());
        vo.setSources(sources);
        return vo;
    }

    /**
     * 检索片段 → 引用来源 VO: 文档名/docId 来自片段元数据, 匹配度 = 相似度分数 × 100,
     * 全文供详情展示, 摘要截断供列表展示
     */
    private RagSourceVO toSource(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Metadata meta = segment.metadata();
        RagSourceVO vo = new RagSourceVO();
        String docId = meta.getString(META_DOC_ID);
        vo.setDocId(docId == null || docId.isBlank() ? null : Long.valueOf(docId));
        vo.setFileName(meta.getString(META_FILE_NAME));
        String page = meta.getString(META_PAGE);
        vo.setPage(page == null || page.isBlank() ? null : Integer.valueOf(page));
        vo.setScore((int) Math.round(match.score() * 100));
        String text = segment.text();
        vo.setText(text);
        vo.setSnippet(RagUtils.snippet(text, SNIPPET_MAX));
        return vo;
    }

    @Override
    public long size() {
        return ragDocumentMapper.sumSegmentCount();
    }
}

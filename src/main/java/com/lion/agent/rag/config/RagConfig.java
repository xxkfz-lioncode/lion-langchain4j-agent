package com.lion.agent.rag.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Rag检索增强
 * <p>
 * 启动时是否把 src/main/resources/docs 下的文档灌入向量库, 由配置
 * {@code lion.rag.ingest-on-startup} 控制(默认 false, 见 application.yml):
 * <ul>
 *   <li>false: 启动只装配检索器, <b>不再重复灌库</b>(Milvus 里的数据是持久化的, 灌一次就够了);</li>
 *   <li>true:  启动时加载 docs 目录并入库 —— 注意这是<b>追加式</b>写入, 每开一次启动都会
 *       重复灌一遍同样的片段(不查重), 改完文档想重新入库时才临时打开;</li>
 * </ul>
 * 常规更新知识请走前端「RAG 知识库」页面上传(带文档管理/删除), 或删掉 Milvus 集合后临时开一次本开关重建。
 */
@Slf4j
@Configuration
public class RagConfig {

    /** 启动时灌库的文档目录 */
    private static final String DOCS_DIR = "src/main/resources/docs";

    @Resource
    private EmbeddingModel qwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    /** 是否在启动时把 docs 目录灌入向量库(默认 false, 防止每次启动重复追加) */
    @Value("${lion.rag.ingest-on-startup:false}")
    private boolean ingestOnStartup;

    @Bean
    public ContentRetriever contentRetriever() {
        // ---- 启动时按需灌库(见类注释, 默认跳过) ----
        if (ingestOnStartup) {
            // 1. 加载文档
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(DOCS_DIR);
            if (documents.isEmpty()) {
                log.warn("[RAG] rag.ingest-on-startup=true 但目录 {} 下没有文档, 跳过入库", DOCS_DIR);
            } else {
                // 2. 文档切分，每个文档按段落进行切分，最大 1000 个字符，每次重叠 200 个字符
                DocumentByParagraphSplitter documentByParagraphSplitter = new DocumentByParagraphSplitter(1000, 200);

                // 3. 自定义文本增强器，把文件名和文本内容拼接后存入向量数据库中
                EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                        .documentSplitter(documentByParagraphSplitter)
                        // 指定 embedding 模型，这里使用 qwenEmbeddingModel
                        .textSegmentTransformer(textSegment -> TextSegment.from(textSegment.metadata().getString("file_name") + "\n" + textSegment.text(), textSegment.metadata()))
                        // 使用的向量模型
                        .embeddingModel(qwenEmbeddingModel)
                        .embeddingStore(embeddingStore)
                        .build();

                // 组装文档
                ingestor.ingest(documents);
                log.info("[RAG] 启动入库完成: {} 个文档来自 {}", documents.size(), DOCS_DIR);
            }
        } else {
            log.info("[RAG] 跳过启动灌库(rag.ingest-on-startup=false), 检索器正常装配");
        }

        // 4. 自定义内容检索器
        // 最多 3 条结果
        // 相似度分数大于 0.75 的结果
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(qwenEmbeddingModel)
                .maxResults(3) // 最多 3 条结果
                .minScore(0.75) // 相似度分数大于 0.75 的结果
                .build();
    }
}
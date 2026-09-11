package com.lion.agent.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RAG 向量库(EmbeddingStore)配置
 * <p>
 * 向量库本体由 <b>langchain4j-milvus-spring-boot-starter</b> 自动装配
 * ({@code MilvusEmbeddingStoreAutoConfiguration}), 连接参数见 application.yml 的
 * {@code langchain4j.milvus.*}。本类只做一件事: 用 &#64;Primary 锁定注入到 RagService 的向量库。
 * <p>
 * 为什么还需要这个类:
 * langchain4j-spring-boot-starter 中的 {@code RagAutoConfig#embeddingStore()} 会额外提供一个
 * &#64;ConditionalOnMissingBean 的 {@code InMemoryEmbeddingStore}; 而 milvus starter 的
 * &#64;ConditionalOnMissingBean 是按 {@link MilvusEmbeddingStore} 类型判断, 内存版不属于该类型,
 * 于是两个 EmbeddingStore Bean 会同时存在, 注入 {@code EmbeddingStore<TextSegment>} 时抛
 * NoUniqueBeanDefinitionException。这里声明一个 EmbeddingStore 类型的 &#64;Primary Bean,
 * 既阻止 InMemoryEmbeddingStore 被创建, 又保证 RagService 拿到的是 Milvus。
 * <p>
 * 前置条件:
 * 1. 需要运行中的 <b>Milvus 2.4+</b> 服务端(低于 2.4 不支持 JSON 字段过滤, removeAll(Filter) 会失败),
 *    本地启动示例:
 *    {@code docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 milvusdb/milvus:latest standalone}
 * 2. collection 会自动创建(id/text/metadata(JSON)/vector 四个字段 + 索引), 无需手工 DDL;
 * 3. dimension 必须与向量化模型输出维度一致(text-embedding-v3 默认 1024)。若同名 collection
 *    已存在且维度不同, 需先手动 drop 旧 collection, 否则插入报维度不匹配。
 */
@Configuration
public class RagVectorStoreConfig {

    /**
     * 把自动装配的 {@link MilvusEmbeddingStore} 提升为 &#64;Primary 的 EmbeddingStore,
     * 消除与 {@code RagAutoConfig} 内 InMemoryEmbeddingStore 的注入歧义。
     */
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> ragVectorStore(MilvusEmbeddingStore milvusEmbeddingStore) {
        return milvusEmbeddingStore;
    }
}

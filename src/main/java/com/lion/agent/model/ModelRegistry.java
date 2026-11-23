package com.lion.agent.model;

import com.lion.agent.model.entity.ModelConfigEntity;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心: 按页面配置(站点/密钥/模型名/参数)动态构建并缓存模型实例。
 * <p>
 * 背景: langchain4j starter 装配的模型 Bean 是启动期静态的(配置来自 application.yml),
 * 页面化管理的配置必须运行时构建 —— OpenAiChatModel/OpenAiEmbeddingModel 都是轻量对象
 * (HTTP 客户端 + 配置), 动态创建成本毫秒级, 完全可行。
 * <p>
 * 缓存策略: key = 类型前缀(chat/emb) + 配置id + 配置hash。配置变更 → service 调
 * {@link #invalidate(Long)} 失效缓存, 下次请求自动按新配置重建; 正在进行中的请求持有
 * 旧实例引用, 不受影响, 切换只作用于新请求 —— 天然平滑。
 */
@Slf4j
@Component
public class ModelRegistry {

    private final ModelKeyCipher cipher;
    private final ChatModelListener chatModelListener;

    /** 缓存: key = "chat:" + configId + ":" + 配置hash */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    /** 缓存: key = "emb:" + configId + ":" + 配置hash */
    private final Map<String, EmbeddingModel> embCache = new ConcurrentHashMap<>();

    public ModelRegistry(ModelKeyCipher cipher, ChatModelListener chatModelListener) {
        this.cipher = cipher;
        this.chatModelListener = chatModelListener;
    }

    /** 取(或构建)指定配置对应的 ChatModel */
    public ChatModel chatOf(ModelConfigEntity config) {
        String key = chatKey(config);
        return chatCache.computeIfAbsent(key, k -> buildChat(config));
    }

    /** 取(或构建)指定配置对应的 EmbeddingModel(向量模型) */
    public EmbeddingModel embeddingOf(ModelConfigEntity config) {
        String key = embKey(config);
        return embCache.computeIfAbsent(key, k -> buildEmbedding(config));
    }

    /** 配置变更(编辑/启停/删除)后失效该配置的全部缓存实例 */
    public void invalidate(Long configId) {
        String prefix = configId + ":";
        chatCache.keySet().removeIf(k -> k.startsWith("chat:" + prefix));
        embCache.keySet().removeIf(k -> k.startsWith("emb:" + prefix));
    }

    private String chatKey(ModelConfigEntity config) {
        return "chat:" + config.getId() + ":" + Objects.hash(
                config.getBaseUrl(), config.getApiKeyCipher(),
                config.getModelName(), config.getTemperature(), config.getTimeoutMs());
    }

    private String embKey(ModelConfigEntity config) {
        // 向量模型无温度概念, hash 不含 temperature
        return "emb:" + config.getId() + ":" + Objects.hash(
                config.getBaseUrl(), config.getApiKeyCipher(),
                config.getModelName(), config.getTimeoutMs());
    }

    /** 按配置构建 ChatModel(密钥在此处才解密, 只进内存, 不落日志) */
    private OpenAiChatModel buildChat(ModelConfigEntity config) {
        log.info("构建模型实例: {} (baseUrl={}, model={})", config.getName(), config.getBaseUrl(), config.getModelName());
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(cipher.decrypt(config.getApiKeyCipher()))
                .modelName(config.getModelName())
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 120000))
                .maxRetries(0)
                .listeners(List.of(chatModelListener));
        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }
        return builder.build();
    }

    /** 按配置构建 EmbeddingModel(向量模型无温度; 维度由服务端返回, 不强配) */
    private OpenAiEmbeddingModel buildEmbedding(ModelConfigEntity config) {
        log.info("构建向量模型实例: {} (baseUrl={}, model={})", config.getName(), config.getBaseUrl(), config.getModelName());
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(cipher.decrypt(config.getApiKeyCipher()))
                .modelName(config.getModelName())
                .timeout(Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 60000))
                .maxRetries(0)
                .build();
    }
}
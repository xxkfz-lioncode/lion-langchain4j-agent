package com.lion.agent.ai.rag.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rerank 精排配置(lion.rag.rerank.*): DashScope gte-rerank 交叉编码器,
 * 对混合检索候选按"问题+片段"逐对打分精排。api-key 复用 QWEN_API_KEY。
 * 精排常开(无开关); 调用失败/超时自动降级为融合排序, 不阻断问答。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lion.rag.rerank")
public class RagRerankProperties {

    /** 精排模型(gte-rerank-v2 为当前推荐版) */
    private String model = "gte-rerank-v2";

    /** DashScope api-key(与问答/向量化同一密钥) */
    private String apiKey = "";

    /** DashScope 原生 API 基址 */
    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** 精排后保留条数(拼入提示词的最终片段数) */
    private int topN = 5;

    /** 精排相关性低于该值的候选剔除(0~1) */
    private double minScore = 0.3;

    /** 单次精排请求超时(毫秒); 超时/异常整体降级为融合排序 */
    private int timeoutMs = 3000;
}
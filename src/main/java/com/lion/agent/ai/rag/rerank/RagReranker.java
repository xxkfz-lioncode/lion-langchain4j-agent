package com.lion.agent.ai.rag.rerank;

import com.lion.agent.ai.rag.search.RetrievedSegment;

import java.util.List;

/**
 * RAG 精排器接口: 交叉编码器对"问题+片段"逐对打分, 按相关性倒序返回。
 * 失败时抛 {@link RerankException}, 调用方捕获后降级为融合排序(rerank 是增强项, 不做单点故障)。
 */
public interface RagReranker {

    /**
     * @param query      用户问题
     * @param candidates 混合检索融合后的候选(顺序即融合排序)
     * @return 按精排相关性倒序的结果, relevance ∈ [0,1]
     */
    List<RerankResult> rerank(String query, List<RetrievedSegment> candidates);

    /** 精排结果: 候选片段 + 交叉编码器相关性分数 */
    record RerankResult(RetrievedSegment segment, double relevance) {
    }

    /** 精排失败(网络/配额/响应异常), 调用方据此降级 */
    class RerankException extends RuntimeException {
        public RerankException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
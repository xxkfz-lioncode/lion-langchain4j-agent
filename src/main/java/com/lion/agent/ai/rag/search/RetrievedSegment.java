package com.lion.agent.ai.rag.search;

import dev.langchain4j.data.segment.TextSegment;

/**
 * 检索候选片段(双路召回的统一载体):
 * 向量路携带相似度分数; BM25 路的 segment 元数据补齐 docId/fileName/page, vectorScore 为 null。
 */
public record RetrievedSegment(TextSegment segment, Double vectorScore) {
}
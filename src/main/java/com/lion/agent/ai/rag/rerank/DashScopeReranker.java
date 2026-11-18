package com.lion.agent.ai.rag.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lion.agent.ai.rag.search.RetrievedSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DashScope gte-rerank 精排实现(原生 REST, 不走 OpenAI 兼容协议 —— rerank 无兼容端点):
 * POST /api/v1/services/rerank/text-rerank/text-rerank
 * 输入 = 问题 + 候选文档文本列表, 输出 = 每个候选的相关性分数(0~1)。
 *
 * 交叉编码器原理: 把"问题+片段"拼成一个输入让模型逐字交互打分, 精度远高于
 * 双塔向量(各自独立编码再算距离), 但必须在线实时计算, 所以只对少量候选精排。
 */
@Slf4j
@Component
public class DashScopeReranker implements RagReranker {

    private final RagRerankProperties props;
    private final RestClient restClient;

    public DashScopeReranker(RagRerankProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getTimeoutMs());
        factory.setReadTimeout(props.getTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<RetrievedSegment> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new RerankException("rerank 未配置 api-key(lion.rag.rerank.api-key / QWEN_API_KEY)", null);
        }
        long start = System.currentTimeMillis();
        List<String> documents = candidates.stream()
                .map(c -> c.segment().text())
                .toList();
        try {
            RerankResponse resp = restClient.post()
                    .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                    .body(new RerankRequest(props.getModel(),
                            new RerankRequest.Input(query, documents),
                            new RerankRequest.Parameters(false, candidates.size())))
                    .retrieve()
                    .body(RerankResponse.class);
            if (resp == null || resp.output() == null || resp.output().results() == null) {
                throw new RerankException("rerank 响应为空", null);
            }
            List<RerankResult> results = new ArrayList<>();
            for (RerankResponse.Item item : resp.output().results()) {
                if (item.index() >= 0 && item.index() < candidates.size()) {
                    results.add(new RerankResult(candidates.get(item.index()), item.relevanceScore()));
                }
            }
            results.sort(Comparator.comparingDouble(RerankResult::relevance).reversed());
            log.info("Rerank 精排完成: {} 个候选, 耗时 {}ms, 最高分 {}",
                    candidates.size(), System.currentTimeMillis() - start,
                    results.isEmpty() ? "-" : results.get(0).relevance());
            return results;
        } catch (RerankException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankException("rerank 调用失败: " + e.getMessage(), e);
        }
    }

    // ---- DashScope 原生协议 DTO(snake_case 字段) ----

    record RerankRequest(String model, Input input, Parameters parameters) {
        record Input(String query, List<String> documents) {
        }

        record Parameters(@JsonProperty("return_documents") boolean returnDocuments,
                          @JsonProperty("top_n") Integer topN) {
        }
    }

    record RerankResponse(Output output) {
        record Output(List<Item> results) {
        }

        record Item(int index, @JsonProperty("relevance_score") double relevanceScore) {
        }
    }
}
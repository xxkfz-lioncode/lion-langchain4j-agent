package com.lion.agent.structured;

import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.structured.extractor.StructuredOutputExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 结构化输出统一门面(包内唯一对外入口)。
 * <p>
 * 包内分层(自外向内):
 * <pre>
 *   com.lion.agent.structured              ── 契约层 + 门面: 本类 / {@link StructuredOutputStrategy} / {@link StructuredTarget}
 *   ├── extractor/                         ── 方式层: 统一接口 + 三种方式实现(各占一个类, 差异一眼可见)
 *   ├── parser/                            ── 支撑层: 模型输出清洗与反序列化(方式层共用)
 *   └── model/                             ── 模型层: 抽取目标类型(演示用 MovieReview)
 * </pre>
 * <p>
 * 用法:
 * <pre>
 *   StructuredTarget&lt;MovieReview&gt; target = MovieReview.target();
 *   MovieReview a = service.extract(text, target, StructuredOutputStrategy.JSON_SCHEMA);
 *   MovieReview b = service.extract(text, target, StructuredOutputStrategy.JSON_MODE);
 *   MovieReview c = service.extract(text, target, StructuredOutputStrategy.PROMPT_ONLY);
 *   StructuredOutputService.FallbackResult&lt;MovieReview&gt; r = service.extractWithFallback(text, target);
 * </pre>
 * <p>
 * 设计要点:
 * <ul>
 *   <li>每种方式一个 {@link StructuredOutputExtractor} 实现(见 {@code extractor} 包), 本类按策略路由;</li>
 *   <li>新增方式(例如接某个厂商私有协议)只需再写一个实现类 + {@code @Component},
 *       Spring 会通过构造器参数 {@code List<StructuredOutputExtractor>} 自动收集,
 *       无需修改本类;</li>
 *   <li>降级顺序 JSON_SCHEMA -> JSON_MODE -> PROMPT_ONLY, 任意一步成功就返回,
 *       沿途失败原因会带回 {@link FallbackResult#failures()} 方便排查。</li>
 * </ul>
 */
@Slf4j
@Service
public class StructuredOutputService {

    private final Map<StructuredOutputStrategy, StructuredOutputExtractor> extractors;

    public StructuredOutputService(List<StructuredOutputExtractor> implementations) {
        this.extractors = implementations.stream()
                .collect(Collectors.toMap(
                        StructuredOutputExtractor::strategy,
                        Function.identity(),
                        // 同策略多个实现时, 保留先注册的并在日志里提示, 避免启动失败
                        (a, b) -> {
                            log.warn("[结构化输出] 策略 {} 有多个实现, 保留先注册的: {}",
                                    a.strategy().label(), a.getClass().getName());
                            return a;
                        }));
        log.info("[结构化输出] 已装配的方式: {}", extractors.keySet());
    }

    /**
     * 按指定方式抽取。失败抛 {@link BusinessException}(可由上层捕获后走降级/重试)。
     */
    public <T> T extract(String input, StructuredTarget<T> target, StructuredOutputStrategy strategy) {
        StructuredOutputExtractor extractor = extractors.get(strategy);
        if (extractor == null) {
            throw new BusinessException("未装配该方式: " + strategy.label());
        }
        return extractor.extract(input, target);
    }

    /**
     * 自动降级: 按 JSON_SCHEMA -> JSON_MODE -> PROMPT_ONLY 顺序尝试, 返回第一个成功的结果。
     * <p>
     * 适用: 不确定当前模型/供应商是否支持 json_schema 时首选这个,
     * 跑不通就自动降到提示词+JSON Mode, 再不行就用纯提示词。
     */
    public <T> FallbackResult<T> extractWithFallback(String input, StructuredTarget<T> target) {
        StructuredOutputStrategy current = StructuredOutputStrategy.JSON_SCHEMA;
        List<String> failures = new ArrayList<>();
        while (true) {
            try {
                T value = extract(input, target, current);
                return new FallbackResult<>(value, current, List.copyOf(failures));
            } catch (Exception e) {
                failures.add(current.label() + ": " + e.getMessage());
                log.warn("[结构化输出] 方式[{}]失败, 准备降级: {}", current.label(), e.toString());
                if (!current.hasFallback()) {
                    throw new BusinessException("三种方式全部失败: " + String.join(" | ", failures));
                }
                current = current.fallback();
            }
        }
    }

    /** 当前已注册的方式 */
    public Set<StructuredOutputStrategy> supportedStrategies() {
        return extractors.keySet();
    }

    /** 自动降级结果: 值 + 最终生效的方式 + 降级过程中每一步的失败原因 */
    public record FallbackResult<T>(T value, StructuredOutputStrategy usedStrategy, List<String> failures) {
    }
}
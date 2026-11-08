package com.lion.agent.text2sql.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.text2sql.config.Text2SqlProperties;
import com.lion.agent.text2sql.entity.DataQueryLogEntity;
import com.lion.agent.text2sql.mapper.DataQueryLogMapper;
import com.lion.agent.text2sql.pipeline.Pipeline;
import com.lion.agent.text2sql.pipeline.PipelineContext;
import com.lion.agent.text2sql.pipeline.PipelineListener;
import com.lion.agent.text2sql.pipeline.PipelineStep;
import com.lion.agent.text2sql.pipeline.PipelineStepException;
import com.lion.agent.text2sql.pipeline.StepEmitter;
import com.lion.agent.text2sql.pipeline.StepMeta;
import com.lion.agent.text2sql.pipeline.StepStatus;
import com.lion.agent.text2sql.pojo.dto.Text2SqlQuery;
import com.lion.agent.text2sql.pojo.vo.Text2SqlResult;
import com.lion.agent.text2sql.service.DataQueryService;
import com.lion.agent.text2sql.service.FewshotStore;
import com.lion.agent.text2sql.service.ResultVisualizer;
import com.lion.agent.text2sql.service.SchemaLinker;
import com.lion.agent.text2sql.service.SqlExecutor;
import com.lion.agent.text2sql.service.SqlGenerator;
import com.lion.agent.text2sql.service.SqlGuard;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 问数编排实现。
 * <pre>
 * 1. 总开关 / 限流 / 缓存
 * 2. SchemaLinker.recall()      → 召回 top-K 表 + schema digest
 * 3. SqlGenerator.generate()    → LLM 生成 SQL(带 few-shot)
 *      └─ clarification 非空   → 直接返回澄清问题, 不执行
 * 4. SqlGuard.check()           → 六层校验; 不通过返回拒绝原因
 * 5. SqlExecutor.execute()      → 只读执行; 失败可错误自愈(最多 N 次)
 * 6. ResultVisualizer.infer()   → 推断图表类型
 * 7. 写 data_query_log 全链路留痕
 * </pre>
 */
@Slf4j
@Service
public class DataQueryServiceImpl implements DataQueryService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_EMPTY = "EMPTY";
    private static final String STATUS_CLARIFICATION = "CLARIFICATION";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_TIMEOUT = "TIMEOUT";

    private final SchemaLinker schemaLinker;
    private final SqlGenerator sqlGenerator;
    private final SqlGuard sqlGuard;
    private final SqlExecutor sqlExecutor;
    private final ResultVisualizer resultVisualizer;
    private final FewshotStore fewshotStore;
    private final DataQueryLogMapper queryLogMapper;
    private final Text2SqlProperties props;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public DataQueryServiceImpl(SchemaLinker schemaLinker,
                                SqlGenerator sqlGenerator,
                                SqlGuard sqlGuard,
                                SqlExecutor sqlExecutor,
                                ResultVisualizer resultVisualizer,
                                FewshotStore fewshotStore,
                                DataQueryLogMapper queryLogMapper,
                                Text2SqlProperties props,
                                RedissonClient redissonClient,
                                ObjectMapper objectMapper) {
        this.schemaLinker = schemaLinker;
        this.sqlGenerator = sqlGenerator;
        this.sqlGuard = sqlGuard;
        this.sqlExecutor = sqlExecutor;
        this.resultVisualizer = resultVisualizer;
        this.fewshotStore = fewshotStore;
        this.queryLogMapper = queryLogMapper;
        this.props = props;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Text2SqlResult query(Text2SqlQuery query, Long userId) {
        if (!StringUtils.hasText(query.getQuestion())) {
            throw new BusinessException("问题不能为空");
        }
        if (!props.isEnabled()) {
            throw new BusinessException("智能问数功能当前不可用");
        }
        checkRateLimit(userId);

        String bizId = UUID.randomUUID().toString().replace("-", "");
        ProgressCollector collector = new ProgressCollector();

        try {
            Text2SqlResult result = doQuery(query, userId, bizId, collector);
            cacheResult(userId, query, result);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[text2sql] 问数失败 bizId={}: {}", bizId, e.getMessage(), e);
            throw new BusinessException("问数失败: " + e.getMessage());
        }
    }

    @Override
    public void queryStream(Text2SqlQuery query, Long userId, Text2SqlEventSink sink) {
        if (!StringUtils.hasText(query.getQuestion())) {
            sink.onError("INVALID_PARAM", "问题不能为空");
            return;
        }
        if (!props.isEnabled()) {
            sink.onError("DISABLED", "智能问数功能当前不可用");
            return;
        }

        String bizId = UUID.randomUUID().toString().replace("-", "");
        ProgressCollector collector = new ProgressCollector(sink);

        try {
            checkRateLimit(userId);
        } catch (Exception e) {
            sink.onError("RATE_LIMIT", e.getMessage());
            return;
        }

        Text2SqlResult cached = loadCache(userId, query);
        if (cached != null) {
            sink.onProgress("命中缓存");
            sink.onResult(cached);
            return;
        }

        try {
            Text2SqlResult result = doQuery(query, userId, bizId, collector);
            cacheResult(userId, query, result);
            sink.onResult(result);
        } catch (Exception e) {
            log.error("[text2sql] 流式问数失败 bizId={}: {}", bizId, e.getMessage(), e);
            sink.onError("ERROR", e.getMessage());
        }
    }

    /**
     * 核心链路(同步/流式共用): 由通用流程编排器(pipeline 包)驱动。
     * <p>
     * 每个业务环节是一个独立步骤, 步骤间通过 PipelineContext 黑板传递产出;
     * 新增环节(如向量召回、NL2SQL 重试)只需 addStep, 不需要改动其它步骤。
     */
    private Text2SqlResult doQuery(Text2SqlQuery query, Long userId, String bizId, ProgressCollector collector) {
        Pipeline pipeline = buildPipeline();

        PipelineContext ctx = new PipelineContext(bizId);
        ctx.put("query", query);
        ctx.put("userId", userId);
        ctx.put("collector", collector);

        try {
            pipeline.run(ctx, collector);
        } catch (PipelineStepException e) {
            // 步骤失败: 还原为业务异常, 上层(query/queryStream)处理方式与重构前保持一致
            if (e.getCause() instanceof BusinessException be) {
                throw be;
            }
            throw e;
        }
        return ctx.get("result");
    }

    /**
     * 流程定义(唯一数据源): doQuery 执行与 describePipeline 元数据查询都从这里取。
     * 新增/调整环节只改这一处。
     */
    private Pipeline buildPipeline() {
        return Pipeline.builder()
                .addStep("recall", "数据表召回", this::stepRecall)
                .addStep("fewshot", "示例检索", this::stepFewshot)
                .addStep("generate", "AI 生成 SQL", this::stepGenerate)
                .addStep("validate", "安全校验", this::stepValidate)
                .addStep("execute", "执行查询", this::stepExecute)
                .addStep("present", "结果组装", this::stepPresent)
                .build();
    }

    @Override
    public List<StepMeta> describePipeline() {
        return buildPipeline().steps().stream()
                .map(s -> new StepMeta(s.id(), s.title()))
                .toList();
    }

    // ==================== 流程步骤 ====================

    /** 步骤1: 召回与问题相关的表 */
    private void stepRecall(PipelineContext ctx, StepEmitter out) {
        String question = question(ctx);
        Long userId = userId(ctx);
        out.detail("正在召回相关数据表...");
        SchemaLinker.RecallResult recall = schemaLinker.recall(question, userId);
        if (recall.tables().isEmpty() || !StringUtils.hasText(recall.schemaDigest())) {
            // 提前收敛: 无召回结果, 直接返回澄清话术, 后续步骤 SKIPPED
            ctx.put("result", clarification(ctx.bizId(), question,
                    "暂未找到与问题相关的数据表, 请换个说法或联系管理员初始化数据模型。", recall, userId));
            ctx.abort();
            return;
        }
        out.detail("已召回表: " + String.join(", ", recall.tables()));
        ctx.put("recall", recall);
    }

    /** 步骤2: 检索相似 few-shot 示例 */
    private void stepFewshot(PipelineContext ctx, StepEmitter out) {
        List<FewshotStore.Example> fewshots = fewshotStore.retrieve(question(ctx), props.getFewshotTopK());
        if (!fewshots.isEmpty()) {
            out.detail("命中 " + fewshots.size() + " 条参考示例");
        }
        ctx.put("fewshots", fewshots);
    }

    /** 步骤3: LLM 生成 SQL(澄清态则提前收敛) */
    private void stepGenerate(PipelineContext ctx, StepEmitter out) {
        String question = question(ctx);
        Long userId = userId(ctx);
        SchemaLinker.RecallResult recall = ctx.get("recall");
        List<FewshotStore.Example> fewshots = ctx.get("fewshots");

        out.detail("AI 正在生成 SQL...");
        SqlGenerator.SqlGenerationResult generated = sqlGenerator.generate(
                question, recall.schemaDigest(), recall.termsHit(), fewshots, userId);

        // 澄清态: 不执行 SQL
        if (StringUtils.hasText(generated.clarification())) {
            out.detail("需要您补充口径");
            Text2SqlResult result = new Text2SqlResult();
            result.setBizId(ctx.bizId());
            result.setQuestion(question);
            result.setClarification(generated.clarification());
            result.setExplanation(generated.explanation());
            result.setIntent(generated.intent());
            result.setStatus(STATUS_CLARIFICATION);
            result.setChartType(ResultVisualizer.TABLE);
            recordLog(ctx.bizId(), userId, question, generated.intent(), recall, null,
                    1, "", 0, 0, STATUS_CLARIFICATION, "", ResultVisualizer.TABLE, null);
            ctx.put("result", result);
            ctx.abort();
            return;
        }

        collector(ctx).sql(generated.sql(), generated.explanation());
        ctx.put("generated", generated);
    }

    /** 步骤4: SQL 安全校验(六层防护) */
    private void stepValidate(PipelineContext ctx, StepEmitter out) {
        String question = question(ctx);
        Long userId = userId(ctx);
        SchemaLinker.RecallResult recall = ctx.get("recall");
        SqlGenerator.SqlGenerationResult generated = ctx.get("generated");

        out.detail("正在做安全校验...");
        SqlGuard.GuardResult guard = sqlGuard.check(generated.sql(), userId);
        if (!guard.passed()) {
            recordLog(ctx.bizId(), userId, question, generated.intent(), recall, generated.sql(),
                    0, guard.rejectReason(), 0, 0, STATUS_ERROR, guard.rejectReason(),
                    ResultVisualizer.TABLE, null);
            throw new BusinessException(guard.rejectReason());
        }
        ctx.put("guard", guard);
    }

    /** 步骤5: 只读执行(含错误自愈重试) */
    private void stepExecute(PipelineContext ctx, StepEmitter out) {
        String question = question(ctx);
        Long userId = userId(ctx);
        SchemaLinker.RecallResult recall = ctx.get("recall");
        List<FewshotStore.Example> fewshots = ctx.get("fewshots");
        SqlGenerator.SqlGenerationResult generated = ctx.get("generated");
        SqlGuard.GuardResult guard = ctx.get("guard");

        out.detail("正在执行查询...");
        SqlExecutor.QueryResult queryResult;
        try {
            queryResult = executeWithSelfHeal(question, recall, fewshots, guard.normalizedSql(),
                    generated.sql(), userId, out);
        } catch (SqlExecutorImpl.SqlExecutionException e) {
            recordLog(ctx.bizId(), userId, question, generated.intent(), recall, generated.sql(),
                    1, "", 0, 0, STATUS_ERROR, e.getMessage(), ResultVisualizer.TABLE, null);
            throw new BusinessException("SQL 执行失败: " + e.getMessage());
        }
        ctx.put("queryResult", queryResult);
    }

    /** 步骤6: 图表推荐 + 结果组装 + 留痕 */
    private void stepPresent(PipelineContext ctx, StepEmitter out) {
        String question = question(ctx);
        SchemaLinker.RecallResult recall = ctx.get("recall");
        SqlGenerator.SqlGenerationResult generated = ctx.get("generated");
        SqlGuard.GuardResult guard = ctx.get("guard");
        SqlExecutor.QueryResult queryResult = ctx.get("queryResult");

        // 可视化推荐: LLM 指定的优先, 否则按结果形态推断
        String chartType = StringUtils.hasText(generated.chartType())
                ? generated.chartType() : resultVisualizer.inferChartType(queryResult.columns(), queryResult.rows());

        Text2SqlResult result = new Text2SqlResult();
        result.setBizId(ctx.bizId());
        result.setQuestion(question);
        result.setSql(guard.normalizedSql());
        result.setExplanation(generated.explanation());
        result.setClarification("");
        result.setIntent(generated.intent());
        result.setColumns(toColumnMeta(queryResult.columns()));
        result.setRows(queryResult.rows());
        result.setTotal(queryResult.total());
        result.setExecuteMs(queryResult.executeMs());
        result.setTruncated(queryResult.truncated());
        result.setChartType(chartType);
        result.setStatus(queryResult.total() == 0 ? STATUS_EMPTY : STATUS_SUCCESS);
        result.setErrorMessage("");

        recordLog(ctx.bizId(), userId(ctx), question, generated.intent(), recall, guard.normalizedSql(),
                1, "", queryResult.executeMs(), queryResult.total(),
                queryResult.total() == 0 ? STATUS_EMPTY : STATUS_SUCCESS, "", chartType,
                buildSnapshot(queryResult));
        ctx.put("result", result);
    }

    /** 错误自愈: 执行报错 → 把错误信息回喂 LLM 重新生成 → 重新校验 → 重新执行。 */
    private SqlExecutor.QueryResult executeWithSelfHeal(String question,
                                                        SchemaLinker.RecallResult recall,
                                                        List<FewshotStore.Example> fewshots,
                                                        String firstSql,
                                                        String originalSql,
                                                        Long userId,
                                                        StepEmitter out) {
        String currentSql = firstSql;
        String lastError = null;

        for (int attempt = 0; attempt <= props.getMaxSelfHealRetry(); attempt++) {
            try {
                return sqlExecutor.execute(currentSql);
            } catch (SqlExecutorImpl.SqlExecutionException e) {
                lastError = e.getMessage();
                if (attempt >= props.getMaxSelfHealRetry()) {
                    throw e;
                }
                log.info("[text2sql] SQL 执行失败, 第 {} 次自愈重试: {}", attempt + 1, lastError);
                out.detail("SQL 执行报错, 正在自动修正(第 " + (attempt + 1) + " 次)...");

                SqlGenerator.SqlGenerationResult regenerated = sqlGenerator.regenerate(
                        question, recall.schemaDigest(), recall.termsHit(), fewshots,
                        currentSql, lastError);
                if (!StringUtils.hasText(regenerated.sql())) {
                    throw e;
                }
                SqlGuard.GuardResult guard = sqlGuard.check(regenerated.sql(), userId);
                if (!guard.passed()) {
                    throw e;
                }
                currentSql = guard.normalizedSql();
            }
        }
        throw new SqlExecutorImpl.SqlExecutionException(lastError, null);
    }

    // ==================== 上下文取值辅助 ====================

    private String question(PipelineContext ctx) {
        Text2SqlQuery query = ctx.get("query");
        return query.getQuestion().trim();
    }

    private Long userId(PipelineContext ctx) {
        return ctx.get("userId");
    }

    private ProgressCollector collector(PipelineContext ctx) {
        return ctx.get("collector");
    }

    // ==================== 限流 / 缓存 ====================

    /**
     * 限流: 每用户每分钟 N 次。Redis 不可用时直接放行(保可用性)。
     */
    private void checkRateLimit(Long userId) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter("text2sql:limit:" + userId);
            limiter.trySetRate(RateType.OVERALL, props.getRateLimitPerMinute(), 1, RateIntervalUnit.MINUTES);
            if (!limiter.tryAcquire(1, 500, TimeUnit.MILLISECONDS)) {
                throw new BusinessException("问数过于频繁, 请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[text2sql] 限流降级(Redis 不可用): {}", e.getMessage());
        }
    }

    private void cacheResult(Long userId, Text2SqlQuery query, Text2SqlResult result) {
        if (result == null || !STATUS_SUCCESS.equals(result.getStatus())) {
            return;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(userId, query));
            bucket.set(objectMapper.writeValueAsString(result),
                    Duration.ofMinutes(props.getCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("[text2sql] 结果缓存写入失败(降级忽略): {}", e.getMessage());
        }
    }

    private Text2SqlResult loadCache(Long userId, Text2SqlQuery query) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(userId, query));
            String json = bucket.get();
            if (StringUtils.hasText(json)) {
                return objectMapper.readValue(json, Text2SqlResult.class);
            }
        } catch (Exception e) {
            log.warn("[text2sql] 结果缓存读取失败(降级忽略): {}", e.getMessage());
        }
        return null;
    }

    private String cacheKey(Long userId, Text2SqlQuery query) {
        String raw = (query.getTableName() == null ? "" : query.getTableName()) + "|" + query.getQuestion().trim();
        return "text2sql:cache:" + userId + ":" + md5(raw);
    }

    private String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    // ==================== 留痕 ====================

    private void recordLog(String bizId, Long userId, String question, String intent,
                           SchemaLinker.RecallResult recall, String sql, int sqlValid,
                           String rejectReason, long executeMs, int rowCount,
                           String resultStatus, String errorMessage, String chartType,
                           String resultSnapshot) {
        try {
            DataQueryLogEntity entity = new DataQueryLogEntity();
            entity.setBizId(bizId);
            entity.setUserId(userId);
            entity.setQuestion(question);
            entity.setIntent(intent == null ? "" : intent);
            entity.setRecalledTables(String.join(",", recall.tables()));
            entity.setTermsHit(recall.termsHit() == null ? "" : String.join(",", recall.termsHit()));
            entity.setGeneratedSql(sql == null ? "" : sql);
            entity.setSqlValid(sqlValid);
            entity.setRejectReason(truncate(rejectReason, 512));
            entity.setExecuteMs(executeMs);
            entity.setRowCount(rowCount);
            entity.setResultStatus(resultStatus);
            entity.setErrorMessage(truncate(errorMessage, 1024));
            entity.setChartType(chartType == null ? "" : chartType);
            entity.setResultSnapshot(resultSnapshot == null ? "" : resultSnapshot);
            queryLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[text2sql] 问数日志写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    /**
     * 结果快照: 全量行序列化为 JSON 供日志详情回溯。
     * 执行链路已在 SQL 层脱敏(applyMasking 改写 AST), 快照数据与用户所见一致, 无泄露风险。
     */
    private String buildSnapshot(SqlExecutor.QueryResult queryResult) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("columns", queryResult.columns().stream()
                    .map(SqlExecutor.ColumnMeta::name).toList());
            snap.put("rows", queryResult.rows());
            return JSONUtil.toJsonStr(snap);
        } catch (Exception e) {
            log.warn("[text2sql] 结果快照序列化失败(忽略): {}", e.getMessage());
            return "";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private List<Text2SqlResult.ColumnMeta> toColumnMeta(List<SqlExecutor.ColumnMeta> columns) {
        List<Text2SqlResult.ColumnMeta> list = new java.util.ArrayList<>(columns.size());
        for (SqlExecutor.ColumnMeta c : columns) {
            list.add(new Text2SqlResult.ColumnMeta(c.name(), c.type()));
        }
        return list;
    }

    private Text2SqlResult clarification(String bizId, String question, String message,
                                         SchemaLinker.RecallResult recall, Long userId) {
        Text2SqlResult result = new Text2SqlResult();
        result.setBizId(bizId);
        result.setQuestion(question);
        result.setClarification(message);
        result.setStatus(STATUS_CLARIFICATION);
        result.setChartType(ResultVisualizer.TABLE);
        recordLog(bizId, userId, question, "CLARIFY", recall, null, 1, "", 0, 0,
                STATUS_CLARIFICATION, "", ResultVisualizer.TABLE, null);
        return result;
    }

    /**
     * 进度收集器: 实现 PipelineListener, 同步模式下丢弃事件, 流式模式下桥接到 SSE sink。
     */
    private static class ProgressCollector implements PipelineListener {
        private final Text2SqlEventSink sink;

        ProgressCollector() {
            this(null);
        }

        ProgressCollector(Text2SqlEventSink sink) {
            this.sink = sink;
        }

        void progress(String message) {
            if (sink != null) {
                sink.onProgress(message);
            }
        }

        void sql(String sql, String explanation) {
            if (sink != null) {
                sink.onSql(sql, explanation);
            }
        }

        @Override
        public void onPlan(List<PipelineStep> steps) {
            if (sink != null) {
                sink.onPlan(steps.stream().map(s -> new StepMeta(s.id(), s.title())).toList());
            }
        }

        @Override
        public void onStepStart(String stepId, String title) {
            if (sink != null) {
                sink.onStep(stepId, title, StepStatus.RUNNING.name(), 0, "");
            }
        }

        @Override
        public void onStepDetail(String stepId, String message) {
            progress(message);
        }

        @Override
        public void onStepFinish(String stepId, String title, StepStatus status, long costMs, String message) {
            if (sink != null) {
                sink.onStep(stepId, title, status.name(), costMs, message);
            }
        }
    }
}

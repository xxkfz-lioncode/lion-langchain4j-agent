package com.lion.agent.text2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 智能问数配置(前缀 {@code lion.text2sql})。
 * <p>
 * 全部配置项见 {@code application.yml} 的 {@code lion.text2sql.*}; 此处给出生产级默认值,
 * 未显式配置时按默认值运行。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lion.text2sql")
public class Text2SqlProperties {

    /** 总开关: false 时问数接口直接返回 503 */
    private boolean enabled = true;

    /** 允许查询的目标库(元数据扫描与问数执行都针对该库) */
    private String database = "lion_agent_4j";

    /** 元数据扫描: 仅扫描表名以该前缀开头的表(留空 = 扫描全部非系统表) */
    private String scanIncludePrefix = "biz_";

    /** 单条查询返回的最大行数(超限截断并标记 truncated) */
    private int maxRows = 10000;

    /** SQL 未显式写 LIMIT 时自动追加的限额 */
    private int defaultLimit = 1000;

    /** 语句执行超时(秒) */
    private int queryTimeoutSeconds = 10;

    /** EXPLAIN 预检: 预计扫描行数上限, 超过则拒绝执行 */
    private long maxExplainRows = 5_000_000L;

    /** Schema 召回数量 */
    private int recallTopK = 5;

    /** Few-shot 召回数量 */
    private int fewshotTopK = 3;

    /** 结果缓存 TTL(分钟) */
    private int cacheTtlMinutes = 10;

    /** 每用户每分钟最大问数次数 */
    private int rateLimitPerMinute = 10;

    /** 元数据定时刷新 cron */
    private String schemaRefreshCron = "0 0 2 * * ?";

    /** 手动刷新是否需要管理员 */
    private boolean refreshRequireAdmin = true;

    /** 只读数据源连接池大小 */
    private int hikariMaxPoolSize = 5;

    /** 是否启用 LLM 字段增强(注释缺失时补全业务含义) */
    private boolean llmColumnEnhance = true;

    /** 最多允许的 JOIN 数量 */
    private int maxJoins = 6;

    /** 错误自愈最大重试次数 */
    private int maxSelfHealRetry = 2;
}

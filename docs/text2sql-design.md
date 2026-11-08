# 智能问数 Agent（Text-to-SQL）设计实现文档

> 版本：v1.0　|　状态：设计评审中　|　适用项目：`lion-langchain4j-agent`
>
> 本文档面向研发落地，包含：总体架构、模块详细设计、数据库 DDL、
> 接口契约、前后端改造点、安全方案、异常降级、评测体系与分期实施计划。

---

## 1. 概述

### 1.1 目标

让用户用**自然语言**直接查询业务数据库，系统自动完成
「意图理解 → 表结构召回 → SQL 生成 → 安全校验 → 执行 → 结果可视化」
全链路，并把每次问数沉淀为可运营的资产。

### 1.2 生产级验收标准

| 维度 | 指标 | 目标值 |
| --- | --- | --- |
| 准确性 | 执行准确率（结果集与标准 SQL 一致） | ≥ 85% |
| 准确性 | SQL 合法率（通过语法 + 语义校验） | ≥ 99% |
| 安全性 | 越权 / 注入拦截率 | 100% |
| 性能 | 端到端 P95（问题 → 结果） | < 10s |
| 稳定性 | 服务可用性 | ≥ 99.9% |
| 可观测 | 问数全链路留痕率 | 100% |

### 1.3 术语

| 术语 | 含义 |
| --- | --- |
| **Schema Linking** | 从全量元数据中召回与问题相关的表 / 字段 |
| **语义层** | 表 / 字段 / 术语 / 口径的增强描述，弥合「数据库结构」与「业务语言」的鸿沟 |
| **SQL Guard** | SQL 生成后的多层安全校验与管控 |
| **Few-shot** | 历史高质量「问题 → SQL」示例，用于检索注入 prompt |
| **RRF** | Reciprocal Rank Fusion，多路召回结果融合排序算法 |

### 1.4 范围边界

**做**：自然语言查数、SQL 解释、结果可视化、问数审计、反馈闭环、只读安全管控。

**不做**（本期）：写操作（INSERT/UPDATE/DELETE）、DDL、跨库联邦查询、
自助建模 / 指标定义平台（列入 P4）。

---

## 2. 总体架构

### 2.1 分层架构

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            前端（Vue3 + Element Plus）                     │
│  DataChatView.vue（问数主界面）                                            │
│   ├─ SchemaBrowser.vue     表 / 字段 / 术语导航，点选辅助提问               │
│   ├─ SqlBlock.vue          SQL 展示 + 口径说明 + 复制                      │
│   ├─ ResultPanel.vue       表格 / 折线 / 柱状 / 饼图 / 指标卡               │
│   └─ FeedbackBar.vue       点赞 / 纠错                                     │
└───────────────────────────────────────┬──────────────────────────────────┘
                                        │ HTTP（axios） + SSE（fetch 流式解析）
┌───────────────────────────────────────▼──────────────────────────────────┐
│                     DataQueryController（REST + SSE）                      │
│   GET  /api/text2sql/stream      流式问数（主入口）                        │
│   POST /api/text2sql/query       同步问数（兜底 / 导出）                   │
│   GET  /api/text2sql/schema      元数据导航                                │
│   POST /api/text2sql/feedback    反馈上报                                  │
│   POST /api/text2sql/schema/refresh  手动刷新元数据                        │
└───────────────────────────────────────┬──────────────────────────────────┘
                                        │
┌───────────────────────────────────────▼──────────────────────────────────┐
│                        DataQueryService（编排层）                          │
│                                                                          │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌────────────┐  │
│   │ SchemaLinker│──▶│ SqlGenerator│──▶│  SqlGuard   │──▶│ SqlExecutor│  │
│   │  召回相关表  │   │  LLM 生成SQL │   │ 六层安全校验 │   │ 只读执行   │  │
│   └─────────────┘   └─────────────┘   └─────────────┘   └────────────┘  │
│          │                 │                                  │          │
│          ▼                 ▼                                  ▼          │
│   ┌─────────────┐   ┌─────────────┐                  ┌────────────┐    │
│   │SchemaManager│   │FewshotStore │                  │ResultVisualizer│ │
│   │ 元数据/语义层│   │ 示例检索     │                  │ 图表类型推断  │   │
│   └─────────────┘   └─────────────┘                  └────────────┘    │
│                                                                          │
│   FeedbackRecorder（全程埋点） ──▶ data_query_log                          │
└──────┬──────────────┬──────────────┬──────────────┬──────────────────────┘
       │              │              │              │
  ┌────▼───┐    ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼──────────┐
  │ MySQL  │    │  Milvus   │  │  Redis    │  │ 业务库（只读账号）│
  │ 元数据  │    │ schema向量 │  │ 缓存/限流  │  │ lion_agent_4j   │
  └────────┘    └───────────┘  └───────────┘  └─────────────────┘
```

### 2.2 与现有架构的复用关系

| 现有能力 | 复用方式 |
| --- | --- |
| `ChatModel` / `StreamingChatModel`（千问） | SQL 生成、意图识别、自然语言解释 |
| `EmbeddingModel`（text-embedding-v3） | 表 / 字段 / 术语向量化 |
| `MilvusEmbeddingStore` | Schema 向量检索（新建 collection） |
| `StructuredOutputService` | 结构化解析 LLM 输出（JSON Schema 方式优先） |
| `R<T>` / `PageResult` / `BusinessException` | 统一返回、分页、异常 |
| `StpUtil`（Sa-Token） | 用户身份、租户隔离、限流维度 |
| Redisson | 限流、分布式锁、结果缓存 |
| Resilience4j | LLM 调用熔断降级 |
| `LlmLog` 表 + Actuator | 调用审计与监控 |
| MyBatis-Plus | 元数据 / 日志 / 反馈持久化 |

### 2.3 包结构（新增）

```
com.lion.agent.ai.text2sql
├── config/
│   ├── Text2SqlProperties.java          # 配置绑定（lion.text2sql.*）
│   ├── ReadOnlyDataSourceConfig.java    # 只读数据源（独立账号）
│   └── SchemaVectorStoreConfig.java     # Milvus schema 集合装配
├── controller/
│   └── DataQueryController.java
├── service/
│   ├── DataQueryService.java            # 编排门面
│   ├── SchemaManager.java               # 元数据扫描 / 语义层
│   ├── SchemaLinker.java                # 多路召回 + RRF 融合
│   ├── SqlGenerator.java                # LLM 生成 + 错误自愈
│   ├── SqlGuard.java                    # 六层安全校验
│   ├── SqlExecutor.java                 # 只读执行 + 预检 + 脱敏
│   ├── ResultVisualizer.java            # 图表类型推断
│   ├── FewshotStore.java                # Few-shot 检索
│   ├── FeedbackService.java             # 反馈与 bad case
│   └── impl/                            # 实现类
├── mapper/
│   ├── DataSchemaTableMapper.java
│   ├── DataSchemaColumnMapper.java
│   ├── DataTermMapper.java
│   ├── DataQueryLogMapper.java
│   ├── DataFewshotMapper.java
│   └── DataMaskRuleMapper.java
├── pojo/
│   ├── entity/                          # 与表一一对应
│   ├── dto/                             # 入参
│   └── vo/                              # 出参
└── util/
    ├── SqlNormalizer.java               # SQL 归一化
    └── RrfMerger.java                   # RRF 融合
```

---

## 3. 技术选型与依赖变更

### 3.1 新增后端依赖（`pom.xml`）

```xml
<!-- SQL 解析与 AST 校验：SqlGuard 的核心，用于语法树级白名单校验 -->
<dependency>
    <groupId>com.github.jsqlparser</groupId>
    <artifactId>jsqlparser</artifactId>
    <version>5.1</version>
</dependency>
```

> 说明：不引入 Druid 作为主数据源（会与现有 MyBatis-Plus 数据源冲突）。
> SQL 静态校验走 **JSqlParser AST**，运行时管控走 **独立只读数据源 +
> `MAX_EXECUTION_TIME` Hint + `setQueryTimeout`**。

### 3.2 新增前端依赖（`frontend/package.json`）

```json
{
  "dependencies": {
    "echarts": "^5.6.0"
  }
}
```

### 3.3 中间件变更

| 中间件 | 变更 |
| --- | --- |
| MySQL | 新增只读账号 `lion_query_ro`（仅 `SELECT` 权限，见 §4.8） |
| Milvus | 新增集合 `lion_text2sql_schema`（dimension=1024，COSINE） |
| Redis | 复用，新增前缀 `text2sql:cache:` / `text2sql:limit:` |

### 3.4 配置变更（`application.yml`）

```yaml
lion:
  text2sql:
    # 总开关
    enabled: true
    # 允许查询的库（多个用逗号分隔；留空 = 当前库）
    database: lion_agent_4j
    # 单条查询返回最大行数
    max-rows: 10000
    # 自动追加的 LIMIT（当 SQL 未显式写 LIMIT 时）
    default-limit: 1000
    # SQL 执行超时（秒）
    query-timeout-seconds: 10
    # EXPLAIN 预检：扫描行数上限，超过则拒绝
    max-explain-rows: 5000000
    # Schema 召回数量
    recall-top-k: 5
    # Few-shot 召回数量
    fewshot-top-k: 3
    # 结果缓存 TTL（分钟）
    cache-ttl-minutes: 10
    # 限流：每用户每分钟最大问数次数
    rate-limit-per-minute: 10
    # 元数据自动刷新时间（cron，每日 02:00）
    schema-refresh-cron: "0 0 2 * * ?"
    # 手动刷新是否需要管理员角色
    refresh-require-admin: true

# 只读数据源（与主数据源隔离；账号仅 SELECT 权限）
spring:
  datasource:
    readonly:
      jdbc-url: jdbc:mysql://${DB_HOST:127.0.0.1}:${DB_PORT:3306}/lion_agent_4j?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
      username: ${DB_QUERY_RO_USERNAME:lion_query_ro}
      password: ${DB_QUERY_RO_PASSWORD:}
      hikari:
        maximum-pool-size: 5
        connection-timeout: 3000
        # 连接级初始化 SQL：强制会话只读（双保险）
        connection-init-sql: "SET SESSION TRANSACTION READ ONLY"
```

---

## 4. 数据库设计（DDL）

> 全部追加到 `docs/sql/init.sql`，遵循项目现有约定：
> InnoDB + utf8mb4 + 列注释 + `create_time` / `update_time`。

### 4.1 表元数据 `data_schema_table`

```sql
DROP TABLE IF EXISTS `data_schema_table`;
CREATE TABLE IF NOT EXISTS `data_schema_table` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `table_name`    VARCHAR(128) NOT NULL COMMENT '表名',
    `table_comment` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '表注释(业务含义)',
    `table_type`    VARCHAR(32)  NOT NULL DEFAULT 'BASE TABLE' COMMENT '表类型',
    `row_count`     BIGINT       NOT NULL DEFAULT 0 COMMENT '估算行数(来自 information_schema)',
    `is_queryable`  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许问数查询: 1 允许 / 0 禁止(敏感表)',
    `schema_version` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '元数据版本(全库刷新批次号)',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_name` (`table_name`),
    KEY `idx_queryable` (`is_queryable`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数表元数据';
```

### 4.2 字段元数据 `data_schema_column`

```sql
DROP TABLE IF EXISTS `data_schema_column`;
CREATE TABLE IF NOT EXISTS `data_schema_column` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `table_name`      VARCHAR(128) NOT NULL COMMENT '表名',
    `column_name`     VARCHAR(128) NOT NULL COMMENT '字段名',
    `data_type`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '数据类型',
    `column_comment`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '字段注释(原始)',
    `llm_description` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT 'LLM 增强后的业务含义',
    `is_nullable`     VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '是否可空: YES/NO',
    `column_key`      VARCHAR(8)   NOT NULL DEFAULT '' COMMENT '键类型: PRI/UNI/MUL',
    `is_fk`           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否外键: 1 是',
    `ref_table`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '外键引用表',
    `ref_column`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '外键引用字段',
    `value_domain`    VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '值域说明(枚举含义, 如 1=待付款/2=已付款)',
    `sample_values`   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '采样值(top-N, 逗号分隔)',
    `is_sensitive`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否敏感字段: 1 是(需脱敏)',
    `schema_version`  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '元数据版本',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_column` (`table_name`, `column_name`),
    KEY `idx_table` (`table_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数字段元数据';
```

### 4.3 业务术语词典 `data_term`

```sql
DROP TABLE IF EXISTS `data_term`;
CREATE TABLE IF NOT EXISTS `data_term` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `term`        VARCHAR(128) NOT NULL COMMENT '术语(如 GMV、活跃用户)',
    `aliases`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '同义词(逗号分隔, 如 成交额,交易额)',
    `definition`  VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '业务定义',
    `table_name`  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主表',
    `column_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主字段',
    `calc_rule`   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '计算口径(自然语言, 如 按下单时间, 不含已取消)',
    `filter_rule` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '附加过滤条件(SQL 片段, 如 status != 5)',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_by`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_term` (`term`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务术语词典';
```

### 4.4 问数日志 `data_query_log`

```sql
DROP TABLE IF EXISTS `data_query_log`;
CREATE TABLE IF NOT EXISTS `data_query_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `biz_id`         VARCHAR(64)  NOT NULL COMMENT '本次问数唯一id(UUID, 串起召回/生成/执行各阶段)',
    `user_id`        BIGINT       NOT NULL COMMENT '用户id',
    `question`       TEXT         NOT NULL COMMENT '用户原始问题',
    `intent`         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '识别出的意图: QUERY/AGGREGATE/TREND/COMPARE/UNKNOWN',
    `recalled_tables` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '召回的表(逗号分隔)',
    `terms_hit`      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '命中的术语(逗号分隔)',
    `generated_sql`  LONGTEXT     NOT NULL COMMENT '生成的 SQL(归一化后)',
    `sql_valid`      TINYINT      NOT NULL DEFAULT 1 COMMENT 'SQL 是否通过校验: 1 通过 / 0 拒绝',
    `reject_reason`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '拒绝原因(校验不通过时)',
    `execute_ms`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'SQL 执行耗时(毫秒)',
    `row_count`      INT          NOT NULL DEFAULT 0 COMMENT '返回行数',
    `result_status`  VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '结果状态: SUCCESS/EMPTY/TIMEOUT/ERROR',
    `error_message`  VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '错误信息',
    `chart_type`     VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '推断的图表类型',
    `schema_version` VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '使用的 schema 版本',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz` (`biz_id`),
    KEY `idx_user` (`user_id`, `create_time`),
    KEY `idx_create` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数日志表';
```

### 4.5 用户反馈 `data_query_feedback`

```sql
DROP TABLE IF EXISTS `data_query_feedback`;
CREATE TABLE IF NOT EXISTS `data_query_feedback` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `biz_id`      VARCHAR(64)  NOT NULL COMMENT '关联 data_query_log.biz_id',
    `user_id`     BIGINT       NOT NULL COMMENT '用户id',
    `rating`      TINYINT      NOT NULL DEFAULT 0 COMMENT '评分: 1 赞 / -1 踩 / 0 未评价',
    `correct_sql` LONGTEXT     NULL COMMENT '用户纠正后的 SQL(踩时可选填)',
    `comment`     VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '文字反馈',
    `handle_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '处理状态: PENDING/HANDLED/IGNORED',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz` (`biz_id`),
    KEY `idx_user` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数反馈表';
```

### 4.6 Few-shot 示例库 `data_fewshot`

```sql
DROP TABLE IF EXISTS `data_fewshot`;
CREATE TABLE IF NOT EXISTS `data_fewshot` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`    TEXT         NOT NULL COMMENT '标准问题',
    `sql`         LONGTEXT     NOT NULL COMMENT '标准 SQL',
    `tables`      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '涉及的表(逗号分隔)',
    `explanation` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '口径说明',
    `avg_rating`  DECIMAL(3,2) NOT NULL DEFAULT 5.00 COMMENT '平均评分(1~5)',
    `use_count`   INT          NOT NULL DEFAULT 0 COMMENT '被注入 prompt 次数',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_enabled` (`enabled`, `avg_rating` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数 Few-shot 示例库';
```

### 4.7 脱敏规则 `data_mask_rule`

```sql
DROP TABLE IF EXISTS `data_mask_rule`;
CREATE TABLE IF NOT EXISTS `data_mask_rule` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `table_name`  VARCHAR(128) NOT NULL COMMENT '表名(* 表示所有表)',
    `column_name` VARCHAR(128) NOT NULL COMMENT '字段名(* 表示所有字段)',
    `mask_type`   VARCHAR(32)  NOT NULL COMMENT '脱敏类型: PHONE/ID_CARD/EMAIL/NAME/BANK_CARD/CUSTOM',
    `expression`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '自定义脱敏 SQL 表达式(仅 CUSTOM 用)',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数脱敏规则表';
```

### 4.8 只读账号（安全底线，必须执行）

```sql
-- 问数专用只读账号：只授予目标库的 SELECT 权限，禁止任何写操作与 DDL
CREATE USER IF NOT EXISTS 'lion_query_ro'@'%' IDENTIFIED BY '换成强密码';
GRANT SELECT ON `lion_agent_4j`.* TO 'lion_query_ro'@'%';
-- 显式回收其它权限，确保最小权限
REVOKE ALL PRIVILEGES ON *.* FROM 'lion_query_ro'@'%';
FLUSH PRIVILEGES;
```

> 生产环境还应通过 `MAX_QUERIES_PER_HOUR` 或代理层（如 ProxySQL
> `max_execution_time`）进一步限制资源。

---

## 5. 后端模块详细设计

### 5.1 元数据与语义层 `SchemaManager`

**职责**：扫描数据库 → 落元数据 → LLM 增强 → 向量化 → 供 SchemaLinker 检索。

#### 5.1.1 扫描算法

```
输入: 配置的数据源
1. information_schema.tables   → 表名 / 注释 / 估算行数 / 表类型
2. information_schema.columns  → 字段 / 类型 / 注释 / 键
3. information_schema.key_column_usage + referential_constraints
                               → 外键关系（JOIN 路径推断）
4. 过滤: 排除视图(VIEW)、系统表、is_queryable=0 的敏感表
5. 批量 UPSERT 到 data_schema_table / data_schema_column
6. 生成 schema_version = yyyyMMddHHmmss（同批次统一）
```

#### 5.1.2 字段增强（LLM 批量补全）

对 `column_comment` 为空或过于简短的字段，按**每批 20 个**调用 LLM：

```text
你是数据库业务专家。根据表名、字段名、采样值，为下列字段生成业务含义与枚举说明。
表: t_order (订单表)
字段列表:
  1. status (tinyint), 采样值: 1,2,3,4,5
  2. pay_channel (varchar), 采样值: alipay,wechat,unionpay
输出 JSON 数组:
[{"column":"status","desc":"订单状态","domain":"1=待付款;2=已付款;3=已发货;4=已完成;5=已取消"}]
```

- 结果写回 `llm_description` / `value_domain`
- 失败降级：保留原始注释，不阻塞扫描
- 触发时机：首次扫描后异步执行；后续刷新仅处理新增/变更字段

#### 5.1.3 向量化

```
表级向量文本:   "{table_name} {table_comment} 包含字段: col1(注释), col2(注释)..."
字段级向量文本: "{table_name}.{column_name} {llm_description} {value_domain}"
```

写入 Milvus 集合 `lion_text2sql_schema`（dimension=1024，COSINE），
metadata 携带 `type(TABLE/COLUMN)`、`table_name`、`column_name`。

#### 5.1.4 接口

```java
public interface SchemaManager {

    /** 全量刷新（启动 + 定时 + 手动触发） */
    void fullRefresh();

    /** 增量刷新（仅处理变化较大的表） */
    void incrementalRefresh();

    /** 构造指定表的 schema 摘要（供 prompt 注入） */
    String buildSchemaDigest(Collection<String> tableNames);

    /** 查询表/字段元数据 */
    List<SchemaTableVO> listTables(String keyword);
    List<SchemaColumnVO> listColumns(String tableName);

    /** 当前 schema 版本 */
    String currentVersion();

    /** 表是否允许查询 */
    boolean isQueryableTable(String tableName);

    /** 字段是否存在 */
    boolean existsColumn(String columnName, List<String> tables);

    /** 根据外键推断 JOIN 路径 */
    List<JoinPath> inferJoinPaths(String fromTable, String toTable);
}
```

#### 5.1.5 刷新策略

| 触发方式 | 说明 |
| --- | --- |
| 启动时 | `@PostConstruct` 检查 `schema_version`，为空则全量刷新；非空跳过 |
| 定时 | `@Scheduled(cron = "${lion.text2sql.schema-refresh-cron}")` 全量刷新 |
| 手动 | `POST /api/text2sql/schema/refresh`（需 admin） |
| DDL 感知 | 可选：监听 `information_schema.update_time` 变化触发增量 |

---

### 5.2 Schema Linking `SchemaLinker`

**职责**：从用户问题召回最相关的 top-K 表。这是决定 SQL 准确率的关键环节。

#### 5.2.1 三路召回 + RRF 融合

```
                    ┌──────────────────────────────────────┐
   用户问题 ───────▶│ ① 关键词召回                        │
                    │   MySQL FULLTEXT / LIKE 匹配         │
                    │   表名、表注释、字段名、字段注释       │
                    └──────────────┬───────────────────────┘
                    ┌──────────────▼───────────────────────┐
                    │ ② 向量召回（Milvus）                  │
                    │   问题 embedding vs schema 向量       │
                    │   top-20, COSINE                    │
                    └──────────────┬───────────────────────┘
                    ┌──────────────▼───────────────────────┐
                    │ ③ 规则召回                           │
                    │   - 问题显式包含表名 → 直接命中        │
                    │   - 命中术语词典 → 取术语绑定表        │
                    │   - 当前会话历史高频表                 │
                    └──────────────┬───────────────────────┘
                                   ▼
                          RRF 融合排序
                    score = Σ 1/(k + rank_i), k=60
                                   ▼
                      取 top-K（默认 5）→ Schema Digest
```

#### 5.2.2 RRF 实现（`util/RrfMerger.java`）

```java
/**
 * 倒数排名融合：不依赖各召回路的分数尺度，只用排名，鲁棒且易调参。
 */
public static List<ScoredTable> merge(List<List<ScoredTable>> sources, int k) {
    Map<String, Double> scoreMap = new HashMap<>();
    Map<String, ScoredTable> detailMap = new HashMap<>();
    for (List<ScoredTable> source : sources) {
        for (int i = 0; i < source.size(); i++) {
            ScoredTable st = source.get(i);
            scoreMap.merge(st.tableName(), 1.0 / (k + i + 1), Double::sum);
            detailMap.putIfAbsent(st.tableName(), st);
        }
    }
    return scoreMap.entrySet().stream()
            .map(e -> new ScoredTable(e.getKey(), e.getValue(), detailMap.get(e.getKey()).reason()))
            .sorted(Comparator.comparingDouble(ScoredTable::score).reversed())
            .toList();
}
```

#### 5.2.3 Schema Digest 格式（注入 prompt）

```text
【表】t_order（订单表，约 120 万行）
  字段: id(主键), user_id(外键→t_user.id), amount(decimal，订单金额),
        status(tinyint，订单状态，1=待付款/2=已付款/3=已发货/4=已完成/5=已取消),
        created_at(datetime，下单时间)
  关联: t_order.user_id → t_user.id
        t_order.id ← t_order_item.order_id
```

#### 5.2.4 接口

```java
public interface SchemaLinker {

    record RecallResult(List<String> tables, String schemaDigest, List<String> termsHit) {}

    /** 主入口 */
    RecallResult recall(String question, Long userId);
}
```

---

### 5.3 SQL 生成 `SqlGenerator`

**职责**：基于 schema digest + few-shot + 术语，调用 LLM 生成 SQL。

#### 5.3.1 结构化输出目标

```java
/**
 * LLM 输出目标（配合 StructuredOutputService 的 JSON_SCHEMA 方式）
 */
public record SqlGenerationResult(
        String sql,              // 生成的 SQL
        String explanation,      // 给用户的口径说明
        String chartType,        // 推断的图表类型
        String clarification,    // 需要澄清时的问题（非空则不执行 SQL）
        String intent            // 意图
) {}
```

#### 5.3.2 Prompt 模板（`resources/prompts/text2sql-system.txt`）

```text
你是资深数据分析专家，负责把业务问题转换为 MySQL 只读查询 SQL。

【硬性规则】
1. 只能生成 SELECT 语句，禁止任何写操作 / DDL。
2. 必须显式列出字段，禁止 SELECT *。
3. 必须带 LIMIT（默认 1000）。
4. 金额字段保留 2 位小数；日期按 created_at 归一到天/周/月。
5. 严格使用下方【表结构】中的字段名，禁止臆造字段。
6. 若问题存在歧义（如时间口径、是否含税），在 clarification 中提问，不要猜测。

【业务术语】
{{terms}}

【相关表结构】
{{schema}}

【相似历史示例】
{{fewshots}}

【输出格式】JSON:
{
  "sql": "SELECT ...",
  "explanation": "按下单城市汇总订单金额，统计周期 2026-10-01 至 2026-10-31",
  "chartType": "bar",
  "clarification": "",
  "intent": "AGGREGATE"
}
```

#### 5.3.3 生成流程

```
1. 判断是否需要澄清（clarification 非空 → 直接返回，前端展示问题，不执行）
2. 调用 StructuredOutputService.extractWithFallback(question, target, JSON_SCHEMA)
3. 失败降级链：JSON_SCHEMA → JSON_MODE → PROMPT_ONLY
4. 二次校验：SQL 归一化（去多余空白/注释）→ SqlGuard 校验
5. 错误自愈：执行报错 → 把错误信息回喂 LLM 重新生成（最多 2 次）
```

#### 5.3.4 接口

```java
public interface SqlGenerator {

    SqlGenerationResult generate(String question, String schemaDigest,
                                 List<String> termsHit, Long userId);

    SqlGenerationResult regenerate(String question, String schemaDigest,
                                   List<String> termsHit, String previousSql,
                                   String errorMessage);
}
```

---

### 5.4 SQL 安全护栏 `SqlGuard`（生产级核心）

#### 5.4.1 六层防线

```
┌─ Layer 1 语法层 ── 正则黑名单快速拦截（毫秒级）
│    禁止: INSERT/UPDATE/DELETE/DROP/ALTER/CREATE/TRUNCATE/GRANT/CALL/...
│          注释(--, #, /* */)、分号多语句、INTO OUTFILE、LOAD_FILE、SLEEP
│
├─ Layer 2 解析层 ── JSqlParser AST 解析
│    解析失败 → 拒绝（语法非法）；语句类型必须是 Select
│
├─ Layer 3 语义层 ── AST 遍历校验
│    表名白名单（data_schema_table.is_queryable = 1）
│    字段存在性校验（对照 data_schema_column）
│    禁止 SELECT *（强制显式列）
│    禁止危险函数（LOAD_FILE/SLEEP/BENCHMARK/...）
│    JOIN 数量 ≤ 6，子查询嵌套 ≤ 3
│
├─ Layer 4 限额层
│    无 LIMIT → 自动追加 LIMIT 1000
│    LIMIT 值 > max-rows → 改写为 max-rows
│
├─ Layer 5 权限层
│    行级权限：按用户角色注入 WHERE 条件（可选）
│    脱敏：改写 SELECT 列表（见 §5.5.3）
│
└─ Layer 6 性能层
     EXPLAIN 预检：扫描行数 > max-explain-rows → 拒绝
```

#### 5.4.2 Layer 1 黑名单正则

```java
private static final Pattern DANGEROUS = Pattern.compile(
      "(?i)("
    + "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|lock|unlock"
    + "|handler|call|prepare|execute|set|reset|flush|kill|shutdown|use|describe|desc|show)\\b"
    + "|(--|#|/\\*)"                        // 注释
    + "|(;\\s*\\S)"                        // 分号后还有内容（多语句）
    + "|(into\\s+(out|dump)file)"           // 写文件
    + "|(load_file|load\\s+data)"           // 读文件
    + "|(sleep|benchmark|pg_sleep)"         // 时间盲注
    + "|(information_schema|mysql\\.|performance_schema|sys\\.)" // 系统库
    + ")"
);
```

> `DESCRIBE` / `SHOW` 一并禁止：它们不是 `SELECT`，且会信息泄露。

#### 5.4.3 Layer 3 语义校验（AST）

```java
public GuardResult check(String sql, Long userId) {
    // 1. 解析
    Statement stmt = CCJSqlParserUtil.parse(sql);          // 失败抛 ParseException
    if (!(stmt instanceof Select select)) {
        return GuardResult.reject("仅允许 SELECT 查询");
    }

    // 2. 表白名单
    List<String> tables = new TablesNamesFinder().getTableList(select);
    for (String t : tables) {
        if (!schemaManager.isQueryableTable(t)) {
            return GuardResult.reject("表不存在或不允许查询: " + t);
        }
    }

    // 3. 字段存在性（遍历 select items + where + group by + order by）
    for (String col : collectColumns(select)) {
        if (!schemaManager.existsColumn(col, tables)) {
            return GuardResult.reject("字段不存在: " + col);
        }
    }

    // 4. 禁止 SELECT *
    if (containsStar(select)) {
        return GuardResult.reject("禁止 SELECT *，请显式指定字段");
    }

    // 5. 危险函数
    if (containsForbiddenFunction(select)) {
        return GuardResult.reject("包含不允许的函数");
    }

    // 6. 复杂度
    if (countJoins(select) > MAX_JOINS) {
        return GuardResult.reject("JOIN 过多（最多 " + MAX_JOINS + "）");
    }

    // 7. LIMIT 归一化 + 8. 脱敏改写
    String normalized = applyMasking(enforceLimit(sql, select), tables);

    // 9. EXPLAIN 预检
    long explainRows = sqlExecutor.explainRows(normalized);
    if (explainRows > maxExplainRows) {
        return GuardResult.reject("预计扫描 " + explainRows + " 行，超过上限，请增加筛选条件");
    }

    return GuardResult.pass(normalized);
}
```

#### 5.4.4 返回结构

```java
public record GuardResult(boolean passed, String normalizedSql, String rejectReason) {
    static GuardResult pass(String sql) { return new GuardResult(true, sql, ""); }
    static GuardResult reject(String reason) { return new GuardResult(false, null, reason); }
}
```

---

### 5.5 执行器 `SqlExecutor`

#### 5.5.1 只读数据源

```java
@Configuration
public class ReadOnlyDataSourceConfig {

    @Bean("readOnlyDataSource")
    public DataSource readOnlyDataSource(
            @Value("${spring.datasource.readonly.jdbc-url}") String url,
            @Value("${spring.datasource.readonly.username}") String username,
            @Value("${spring.datasource.readonly.password}") String password,
            Text2SqlProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(props.getHikariMaxPoolSize());
        // 连接级只读 + 查询超时双保险
        ds.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
        return ds;
    }

    @Bean("readOnlyJdbcTemplate")
    public JdbcTemplate readOnlyJdbcTemplate(@Qualifier("readOnlyDataSource") DataSource ds,
                                             Text2SqlProperties props) {
        JdbcTemplate tpl = new JdbcTemplate(ds);
        tpl.setQueryTimeout(props.getQueryTimeoutSeconds());
        tpl.setMaxRows(props.getMaxRows());
        return tpl;
    }
}
```

#### 5.5.2 执行流程

```
1. EXPLAIN <sql> → 取 rows 总和（性能预检）
2. 拼装 Hint: /*+ MAX_EXECUTION_TIME(10000) */ <sql>
3. Statement.setQueryTimeout(10)
4. Statement.setMaxRows(10000)
5. 执行 → 封装 ResultSet → columns + rows
6. 截断标记 truncated = (rows.size() == maxRows)
7. 记录耗时
```

> `MAX_EXECUTION_TIME` Hint 是 MySQL 服务端级超时，
> 即使客户端断开，服务端也会终止查询，避免慢查询堆积。

#### 5.5.3 脱敏改写

在 SqlGuard 的 Layer 5，根据 `data_mask_rule` 改写 SELECT 列表：

| 类型 | 改写表达式 |
| --- | --- |
| PHONE | `CONCAT(LEFT(col,3),'****',RIGHT(col,4)) AS col` |
| ID_CARD | `CONCAT(LEFT(col,6),'********',RIGHT(col,4)) AS col` |
| EMAIL | `CONCAT(LEFT(col,2),'***@',SUBSTRING_INDEX(col,'@',-1)) AS col` |
| NAME | `CONCAT(LEFT(col,1),'**') AS col` |
| CUSTOM | 使用 `expression` 模板（`{col}` 占位） |

#### 5.5.4 接口

```java
public interface SqlExecutor {

    /** 执行查询，返回结构化结果 */
    QueryResult execute(String sql);

    /** EXPLAIN 预检：返回预计扫描行数 */
    long explainRows(String sql);

    record QueryResult(List<ColumnMeta> columns, List<List<Object>> rows,
                       int total, long executeMs, boolean truncated) {}

    record ColumnMeta(String name, String type) {}
}
```

---

### 5.6 结果可视化推断 `ResultVisualizer`

#### 5.6.1 推断规则

```
输入: columns[], rows[]
1. rows 为空 → EMPTY
2. 只有 1 列且只有 1 行 → METRIC（指标卡）
3. 只有 1 列且行数 > 1 → TABLE
4. 2 列:
   - 第 1 列是日期/时间类型 → LINE
   - 第 1 列是类别且行数 ≤ 8 → PIE
   - 否则 → BAR
5. ≥3 列:
   - 第 1 列是日期/时间 → LINE（多系列）
   - 否则 → BAR（多系列）或 TABLE
6. 列名含 "占比/percent/ratio" 且行数 ≤ 8 → PIE
7. 兜底 → TABLE
```

#### 5.6.2 接口

```java
public interface ResultVisualizer {
    String inferChartType(List<SqlExecutor.ColumnMeta> columns,
                          List<List<Object>> rows);
}
```

---

### 5.7 Few-shot 检索 `FewshotStore`

```java
public interface FewshotStore {

    /** 检索最相似的 few-shot 示例（向量 + 关键词混合） */
    List<FewshotVO> retrieve(String question, int topK);

    /** 使用计数 +1（用于热度排序） */
    void incrementUseCount(Long id);
}
```

检索策略：`data_fewshot.enabled=1 AND avg_rating >= 4.0` 的示例，
按 `use_count DESC, avg_rating DESC` 结合向量相似度排序。

---

### 5.8 反馈与 bad case `FeedbackService`

```java
public interface FeedbackService {

    /** 提交反馈 */
    void submit(FeedbackRequest request);

    /** 待处理的 bad case 列表（运营后台） */
    PageResult<BadCaseVO> listBadCases(int page, int size);

    /** 标记 bad case 已处理（可选：自动沉淀为 few-shot） */
    void markHandled(Long feedbackId, boolean promoteToFewshot);
}
```

**闭环流程**：

```
用户踩 + 纠错 SQL
   ↓
data_query_feedback (PENDING)
   ↓
运营/LLM 分析根因
   ├─ schema 描述不清 → 更新 data_term / 触发字段增强
   ├─ SQL 生成错误   → 审核后写入 data_fewshot（promoteToFewshot）
   └─ 口径问题       → 更新 data_term.calc_rule
   ↓
data_query_feedback (HANDLED)
```

---

### 5.9 编排门面 `DataQueryService`

```java
public interface DataQueryService {

    /** 同步版（供 Controller 兜底 / 导出调用） */
    Text2SqlResult query(Text2SqlQuery query, Long userId);

    /** 流式版：通过回调逐阶段推送（SSE） */
    void queryStream(Text2SqlQuery query, Long userId, Text2SqlEventSink sink);

    /** 事件回调接口 */
    interface Text2SqlEventSink {
        void onProgress(String message);
        void onSql(String sql, String explanation);
        void onResult(Text2SqlResult result);
        void onError(String type, String message);
    }
}
```

#### 5.9.1 编排时序

```
queryStream(question, userId, sink):
  1. 限流检查（Redisson）           → 超限直接 onError
  2. 缓存检查（Redis）              → 命中直接 onResult
  3. bizId = UUID
  4. SchemaLinker.recall()          → sink.onProgress("已召回表: t_order, t_user")
  5. SqlGenerator.generate()        → sink.onSql(sql, explanation)
       └─ clarification 非空 → sink.onResult(clarification 版) 并结束
  6. SqlGuard.check()               → 不通过 → onError(SQL_INVALID)
  7. SqlExecutor.execute()
       ├─ EXPLAIN 预检失败 → onError(EXPLAIN_REJECT)
       ├─ 超时 → onError(EXEC_TIMEOUT)
       └─ 成功 → ResultVisualizer.inferChartType()
  8. sink.onResult(...)
  9. FeedbackRecorder.record(bizId, 全流程埋点)
```

---

### 5.10 Controller 与 SSE 协议

```java
@Tag(name = "智能问数", description = "自然语言查数据库（只读，需登录）")
@RestController
@RequestMapping("/api/text2sql")
public class DataQueryController {

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("question") String question,
                             @RequestParam(value = "conversationId", required = false) Long conversationId) {
        // 与 ChatController 一致：SseEmitter + 回调
    }
}
```

**SSE 事件协议**（单 `data:` 流，JSON 带 `type` 字段，兼容现有前端解析器）：

```
data: {"type":"progress","message":"正在召回相关表..."}

data: {"type":"sql","sql":"SELECT city, SUM(amount) ...","explanation":"按下单城市汇总..."}

data: {"type":"result","columns":[{"name":"city","type":"varchar"},{"name":"total","type":"decimal"}],
       "rows":[["北京",128000.00],["上海",96000.00]],
       "total":2,"executeMs":820,"truncated":false,"chartType":"bar",
       "bizId":"b3f2...","sql":"SELECT ..."}

data: {"type":"error","errorType":"SQL_INVALID","message":"禁止 SELECT *，请显式指定字段"}

data: [DONE]
```

---

## 6. 接口契约

### 6.1 流式问数（主入口）

```http
GET /api/text2sql/stream?question=上个月各城市的订单金额&conversationId=123
Accept: text/event-stream
```

响应：见 §5.10 SSE 协议。

### 6.2 同步问数（兜底 / 导出）

```http
POST /api/text2sql/query
Content-Type: application/json

{ "question": "上个月各城市的订单金额", "conversationId": 123 }
```

响应：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "bizId": "b3f2...",
    "sql": "SELECT city, SUM(amount) AS total FROM t_order WHERE ... LIMIT 1000",
    "explanation": "按下单城市汇总订单金额，统计周期 2026-10-01 至 2026-10-31",
    "columns": [{"name":"city","type":"varchar"},{"name":"total","type":"decimal"}],
    "rows": [["北京",128000.00]],
    "total": 1,
    "executeMs": 820,
    "truncated": false,
    "chartType": "bar",
    "clarification": ""
  }
}
```

### 6.3 元数据导航

```http
GET /api/text2sql/schema/tables?keyword=订单
GET /api/text2sql/schema/columns?tableName=t_order
GET /api/text2sql/schema/terms
GET /api/text2sql/schema/join-path?from=t_order&to=t_user
```

### 6.4 反馈

```http
POST /api/text2sql/feedback
{
  "bizId": "b3f2...",
  "rating": -1,
  "correctSql": "SELECT city, SUM(amount) FROM t_order WHERE status=4 ...",
  "comment": "应该只统计已完成订单"
}
```

### 6.5 元数据刷新

```http
POST /api/text2sql/schema/refresh        # 需 admin
```

---

## 7. 前端设计

### 7.1 路由（`router/index.js` 追加）

```js
// 分类二: 智能分析
{
  path: 'text2sql',
  name: 'text2sql',
  component: () => import('@/views/DataChatView.vue'),
  meta: { title: '智能问数', parentTitle: '智能分析', requiresAuth: true }
},
```

### 7.2 页面布局（`DataChatView.vue`）

```
┌──────────────┬───────────────────────────────────────────────────┐
│ Schema 导航   │  对话区                                           │
│              │  ┌─ 用户气泡 ─────────────────────────────┐        │
│ 🔍 搜索表...  │  └────────────────────────────────────────┘        │
│              │  ┌─ AI 气泡 ───────────────────────────────┐       │
│ ▸ t_order    │  │ [SQL 块] SELECT city, SUM(amount) ...    │       │
│   订单表      │  │ [口径] 按下单城市汇总...                  │       │
│   · amount   │  │ [结果] 📊 柱状图 | 📋 表格 | ⬇️ 导出       │       │
│   · status   │  │ [反馈] 👍 👎                             │       │
│ ▸ t_user     │  └────────────────────────────────────────┘       │
│ ▸ t_item     │                                                   │
│ ──────────   │                                                   │
│ 业务术语      │                                                   │
│ GMV: 订单金额 │                                                   │
│ ──────────   │                                                   │
│ 快捷问题      │                                                   │
│ · 销售额趋势  │                                                   │
│ · TOP10 商品 │                                                   │
│ · 用户增长   │                                                   │
├──────────────┴───────────────────────────────────────────────────┤
│ [输入自然语言问题...]                                  [发送]     │
└───────────────────────────────────────────────────────────────────┘
```

### 7.3 组件清单

| 组件 | 职责 |
| --- | --- |
| `SchemaBrowser.vue` | 表/字段/术语树，支持搜索，点击字段可追加到输入框 |
| `SqlBlock.vue` | SQL 语法高亮（可用 `highlight.js`）、口径说明、复制、EXPLAIN 信息 |
| `ResultPanel.vue` | 表格 / ECharts 图表切换、导出 CSV |
| `FeedbackBar.vue` | 👍/👎 + 纠错输入框 |
| `TypingIndicator.vue` | 思考态（复用 `McpView` 的 `typing-indicator` 样式） |

### 7.4 API 封装（`api/text2sql.js`）

```js
import request from './request'

/** 流式问数：fetch + ReadableStream 解析（与 chat.js 一致的 SSE 解析方式） */
export function streamQuery(question, conversationId, onEvent) {
  const url = `/api/text2sql/stream?question=${encodeURIComponent(question)}`
              + (conversationId ? `&conversationId=${conversationId}` : '')
  return fetch(url, { headers: { Accept: 'text/event-stream', 'sa-token': getToken() } })
    .then(res => parseSse(res, onEvent))   // 复用 chat.js 的解析器
}

export const querySync      = (data) => request({ url: '/api/text2sql/query', method: 'post', data })
export const listTables     = (keyword) => request({ url: '/api/text2sql/schema/tables', params: { keyword } })
export const listColumns    = (tableName) => request({ url: '/api/text2sql/schema/columns', params: { tableName } })
export const submitFeedback = (data) => request({ url: '/api/text2sql/feedback', method: 'post', data })
```

> **复用点**：`chat.js` / `mcp.js` 中的 SSE 解析逻辑应抽成公共
> `api/sse.js`，三处共用，避免重复。

### 7.5 交互细节

1. **流式阶段提示**：召回 → 生成 → 校验 → 执行，每个阶段显示不同加载文案
2. **澄清态**：`clarification` 非空时，不显示 SQL，只显示问题 + 输入框聚焦
3. **SQL 可编辑**：高级用户可直接编辑 SQL 后重新执行（生成 → 校验 → 执行）
4. **结果导出**：CSV / Excel 导出当前结果
5. **会话保持**：问数也走 `conversationId`，支持多轮追问

---

## 8. 安全设计

### 8.1 威胁模型与对策

| 威胁 | 对策 |
| --- | --- |
| SQL 注入 / 写操作 | Layer 1 黑名单 + Layer 2 只允许 SELECT |
| 越权查询敏感表 | Layer 3 表白名单（`is_queryable=0` 的表不可查） |
| 拖库（全表扫描） | Layer 6 EXPLAIN 预检 + LIMIT + 超时 |
| 敏感数据泄露 | Layer 5 脱敏改写 + 字段标记 `is_sensitive` |
| 系统库探测 | Layer 1 禁止 `information_schema` / `mysql.*` |
| 时间盲注 | Layer 1 禁止 `SLEEP` / `BENCHMARK` |
| 多语句攻击 | Layer 1 禁止分号后还有内容 |
| 资源耗尽 | 限流 + 连接池上限 + 语句超时 |
| 审计缺失 | `data_query_log` 全量留痕 |

### 8.2 账号权限矩阵

| 账号 | 权限 | 用途 |
| --- | --- | --- |
| `root` / 应用账号 | 全部 | 元数据扫描、业务读写 |
| `lion_query_ro` | 仅 `SELECT` | **问数执行专用** |

### 8.3 越权隔离

```
表级: is_queryable = 0 → 禁止出现在任何 SQL 中
字段级: is_sensitive = 1 → 必须脱敏
行级: 按用户角色注入 WHERE（P3 可选）
```

### 8.4 审计要求

- 每次问数记录：用户、原始问题、召回表、生成 SQL、校验结果、执行耗时、行数
- 敏感操作（触发 EXPLAIN 拒绝、命中黑名单）单独标记并告警
- 日志保留 ≥ 90 天

---

## 9. 异常与降级

### 9.1 错误码与处理

| 错误类型 | 场景 | 前端表现 |
| --- | --- | --- |
| `SQL_INVALID` | 校验不通过 | 展示拒绝原因 + 建议修改 |
| `EXPLAIN_REJECT` | 扫描行数超限 | 提示增加筛选条件 |
| `EXEC_TIMEOUT` | 执行超时 | 提示缩小时间范围 |
| `EXEC_ERROR` | SQL 执行报错 | 展示错误信息 + 自动重试 1 次 |
| `LLM_ERROR` | 模型调用失败 | 降级提示 + 重试 |
| `RATE_LIMIT` | 限流 | 提示稍后再试 |
| `CLARIFICATION` | 需要澄清 | 展示问题，等待用户回答 |

### 9.2 降级策略

```
LLM 调用失败
  → Resilience4j 熔断（滑动窗口 1min，阈值 50%）
  → 重试 1 次（指数退避）
  → 仍失败 → 返回友好提示 + 记录告警

SQL 执行失败
  → 自动错误自愈（最多 2 次，把错误信息回喂 LLM）
  → 仍失败 → 返回错误 + 原始 SQL（供用户排查）

Milvus 不可用
  → SchemaLinker 降级为纯关键词召回（准确率下降但可用）

Redis 不可用
  → 跳过缓存与限流，直接执行（保证可用性）
```

### 9.3 兜底规则

- 总开关 `lion.text2sql.enabled=false` 时，接口返回 503 + 提示
- 元数据未初始化时，问数接口返回「数据模型尚未初始化，请联系管理员」

---

## 10. 非功能性设计

### 10.1 性能

| 环节 | 目标 | 手段 |
| --- | --- | --- |
| Schema 召回 | < 200ms | Milvus 向量 + MySQL 索引 |
| SQL 生成 | 2~8s | LLM（流式） |
| SQL 执行 | < 5s | 预检 + 超时 + 索引 |
| 端到端 | P95 < 10s | 全链路 |
| 缓存命中 | < 500ms | Redis |

### 10.2 缓存设计

```
key:   text2sql:cache:{MD5(question + schemaVersion + userId)}
value: Text2SqlResult JSON
TTL:   10 分钟（可配）
```

- 仅缓存 `SUCCESS` 结果
- schema 刷新后版本号变化 → 缓存自然失效

### 10.3 限流设计（Redisson）

```
key:   text2sql:limit:{userId}
规则:  10 次 / 分钟（滑动窗口）
超限:  返回 RATE_LIMIT
```

### 10.4 监控与告警（Actuator + 现有 LlmLog）

| 指标 | 说明 |
| --- | --- |
| `text2sql.query.qps` | 问数 QPS |
| `text2sql.query.latency` | 端到端耗时 |
| `text2sql.sql.reject.rate` | SQL 拒绝率 |
| `text2sql.sql.error.rate` | 执行错误率 |
| `text2sql.feedback.negative` | 负反馈数 |

### 10.5 容量规划

| 资源 | 建议 |
| --- | --- |
| 只读数据源连接池 | 5~10 |
| 单次返回行数 | ≤ 10000 |
| 单次返回体积 | ≤ 5MB |
| 每日问数量 | 按 1000 次/日 设计，预留 10 倍 |

---

## 11. 测试与评测

### 11.1 评测集构建

构建 **50~100 条「问题 → 标准 SQL」测试集**，覆盖：

| 类型 | 数量 | 示例 |
| --- | --- | --- |
| 单表简单查询 | 15 | 「查询今天的订单数量」 |
| 单表聚合 | 15 | 「各城市的订单金额」 |
| 多表 JOIN | 15 | 「各用户的订单数」 |
| 日期处理 | 10 | 「最近 7 天的趋势」 |
| 歧义澄清 | 5 | 「销售额」（口径不明） |
| 越权/注入 | 10 | 「查一下用户密码」 |

### 11.2 评测指标

| 指标 | 定义 | 目标 |
| --- | --- | --- |
| 执行准确率 | 生成 SQL 结果与标准 SQL 一致 | ≥ 85% |
| Exact Match | SQL 文本完全一致 | ≥ 60% |
| SQL 合法率 | 语法 + 语义校验通过 | ≥ 99% |
| 安全拦截率 | 越权/注入被拦截 | 100% |
| 平均耗时 | 问题 → 结果 | < 8s |

### 11.3 回归测试

每次变更（prompt / schema / 模型版本）跑一遍评测集，指标下降 > 3% 则阻断发布。

### 11.4 单元测试要点

- `SqlGuard`：覆盖全部危险 SQL 用例
- `SchemaLinker`：召回准确率
- `ResultVisualizer`：图表类型推断
- `SqlExecutor`：超时、截断、脱敏

---

## 12. 配置项汇总

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `lion.text2sql.enabled` | true | 总开关 |
| `lion.text2sql.max-rows` | 10000 | 最大返回行数 |
| `lion.text2sql.default-limit` | 1000 | 自动 LIMIT |
| `lion.text2sql.query-timeout-seconds` | 10 | 执行超时 |
| `lion.text2sql.max-explain-rows` | 5000000 | EXPLAIN 扫描行数上限 |
| `lion.text2sql.recall-top-k` | 5 | Schema 召回数 |
| `lion.text2sql.fewshot-top-k` | 3 | Few-shot 数 |
| `lion.text2sql.cache-ttl-minutes` | 10 | 缓存 TTL |
| `lion.text2sql.rate-limit-per-minute` | 10 | 限流 |
| `lion.text2sql.schema-refresh-cron` | `0 0 2 * * ?` | 刷新时间 |
| `spring.datasource.readonly.*` | — | 只读数据源 |

---

## 13. 分期实施计划

### P1 MVP（1~2 周）

**目标**：单库、简单查询跑通，能上线试用。

- [ ] DDL 落地 + 只读账号
- [ ] `SchemaManager`：元数据扫描 + 基础语义层
- [ ] `SchemaLinker`：关键词召回（暂不接向量）
- [ ] `SqlGenerator`：单阶段 NL→SQL
- [ ] `SqlGuard`：Layer 1~4（黑名单 + AST + 限额）
- [ ] `SqlExecutor`：只读数据源 + 超时 + 截断
- [ ] `DataQueryService` + SSE
- [ ] `DataChatView`：表格结果 + SQL 展示
- [ ] 评测集 v0（20 条）

### P2 增强（2 周）

**目标**：多表 + 中文口径 + 可视化。

- [ ] Milvus schema 向量 + 向量召回 + RRF
- [ ] `data_term` 术语词典维护
- [ ] Few-shot 检索与注入
- [ ] `ResultVisualizer` + ECharts
- [ ] 反馈入口 + `data_query_log` 埋点
- [ ] EXPLAIN 预检 + 脱敏
- [ ] 评测集 v1（60 条）

### P3 生产（2 周）

**目标**：安全 + 闭环 + 可运营。

- [ ] 行级权限
- [ ] Redis 缓存 + 限流
- [ ] 异步大查询
- [ ] bad case 分析后台
- [ ] 监控告警
- [ ] 评测集 v2（100 条）+ 回归流水线

### P4 平台（3 周）

**目标**：多数据源 + 指标化。

- [ ] 多数据源接入
- [ ] 指标定义平台
- [ ] 数据服务 API
- [ ] 告警订阅

---

## 14. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Schema 过大，prompt 爆炸 | 准确率下降 | 分层检索，只注入 top-K 表 |
| 中文歧义（口径不清） | SQL 错误 | 术语词典 + 澄清机制 |
| SQL 生成错误 | 查错数据 | 错误自愈 + Few-shot + 校验 |
| 慢查询拖垮库 | 系统稳定性 | EXPLAIN 预检 + 超时 + 只读账号 |
| 数据泄露 | 安全事件 | 白名单 + 脱敏 + 行级权限 + 审计 |
| LLM 不稳定 | 服务不可用 | 熔断 + 降级 + 重试 |
| 模型幻觉字段 | SQL 报错 | 字段存在性校验 + 错误回喂 |
| 元数据过期 | 召回错误 | 定时刷新 + 版本号 + 手动刷新 |

---

## 15. 附录

### 15.1 关键类清单

| 类 | 职责 |
| --- | --- |
| `DataQueryController` | REST + SSE 入口 |
| `DataQueryService` | 编排门面 |
| `SchemaManager` | 元数据扫描 / 语义层 / 向量化 |
| `SchemaLinker` | 三路召回 + RRF |
| `SqlGenerator` | LLM 生成 + 自愈 |
| `SqlGuard` | 六层安全校验 |
| `SqlExecutor` | 只读执行 + 预检 + 脱敏 |
| `ResultVisualizer` | 图表推断 |
| `FewshotStore` | 示例检索 |
| `FeedbackService` | 反馈与 bad case |
| `ReadOnlyDataSourceConfig` | 只读数据源 |
| `Text2SqlProperties` | 配置绑定 |
| `RrfMerger` | RRF 融合 |
| `SqlNormalizer` | SQL 归一化 |

### 15.2 新增文件清单（预估）

| 类型 | 数量 |
| --- | --- |
| Entity | 7 |
| Mapper | 6 |
| Service 接口 + 实现 | 22 |
| Controller | 1 |
| Config | 3 |
| Util | 2 |
| DTO / VO | 10 |
| 前端页面 | 1 |
| 前端组件 | 5 |
| 前端 API | 1 |
| Prompt 模板 | 1 |
| 测试类 | 8 |

### 15.3 参考实现要点

1. **结构化输出优先用 `JSON_SCHEMA`**：复用 `StructuredOutputService`，
   模型兼容性最好；失败自动降级到 `JSON_MODE`。
2. **SQL 执行必须走只读数据源**：不要用主数据源，账号只给 `SELECT`。
3. **先校验后执行**：任何 LLM 生成的 SQL 都必须过 `SqlGuard`。
4. **全链路留痕**：`bizId` 串起召回 / 生成 / 校验 / 执行 / 反馈。
5. **反馈是资产**：`data_fewshot` 与 `data_term` 越用越值钱。

---

> 本文档为设计稿，落地时以实际表结构、字段注释、业务口径为准进行调整。


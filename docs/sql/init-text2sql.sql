-- ============================================================
-- 智能问数(Text-to-SQL)增量建表脚本
-- 用途: 已按 init.sql 初始化过库的实例, 只补问数相关表, 不动 sys_user / chat_* 等已有数据
-- 执行: mysql -uroot -p --default-character-set=utf8mb4 < init-text2sql.sql
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `lion_agent_4j`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `lion_agent_4j`;

-- ---------- 1. 表元数据 ----------
DROP TABLE IF EXISTS `data_schema_table`;
CREATE TABLE IF NOT EXISTS `data_schema_table` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `table_name`     VARCHAR(128) NOT NULL COMMENT '表名',
    `table_comment`  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '表注释(业务含义)',
    `table_type`     VARCHAR(32)  NOT NULL DEFAULT 'BASE TABLE' COMMENT '表类型',
    `row_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '估算行数',
    `is_queryable`   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许问数查询: 1 允许 / 0 禁止(敏感表)',
    `schema_version` VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '元数据版本(全库刷新批次号)',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_name` (`table_name`),
    KEY `idx_queryable` (`is_queryable`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数表元数据';

-- ---------- 2. 字段元数据 ----------
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

-- ---------- 3. 业务术语词典 ----------
DROP TABLE IF EXISTS `data_term`;
CREATE TABLE IF NOT EXISTS `data_term` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `term`        VARCHAR(128) NOT NULL COMMENT '术语(如 GMV、活跃用户)',
    `aliases`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '同义词(逗号分隔)',
    `definition`  VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '业务定义',
    `table_name`  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主表',
    `column_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主字段',
    `calc_rule`   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '计算口径(自然语言)',
    `filter_rule` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '附加过滤条件(SQL 片段)',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_by`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_term` (`term`),
    KEY `idx_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务术语词典';

-- ---------- 4. 问数日志 ----------
DROP TABLE IF EXISTS `data_query_log`;
CREATE TABLE IF NOT EXISTS `data_query_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `biz_id`          VARCHAR(64)  NOT NULL COMMENT '本次问数唯一id(UUID, 串起召回/生成/执行各阶段)',
    `user_id`         BIGINT       NOT NULL COMMENT '用户id',
    `question`        TEXT         NOT NULL COMMENT '用户原始问题',
    `intent`          VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '识别出的意图',
    `recalled_tables` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '召回的表(逗号分隔)',
    `terms_hit`       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '命中的术语(逗号分隔)',
    `generated_sql`   LONGTEXT     NOT NULL COMMENT '生成的 SQL(归一化后)',
    `sql_valid`       TINYINT      NOT NULL DEFAULT 1 COMMENT 'SQL 是否通过校验: 1 通过 / 0 拒绝',
    `reject_reason`   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '拒绝原因',
    `execute_ms`      BIGINT       NOT NULL DEFAULT 0 COMMENT 'SQL 执行耗时(毫秒)',
    `row_count`       INT          NOT NULL DEFAULT 0 COMMENT '返回行数',
    `result_status`   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '结果状态: SUCCESS/EMPTY/TIMEOUT/ERROR',
    `error_message`   VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '错误信息',
    `chart_type`      VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '推断的图表类型',
    `schema_version`  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '使用的 schema 版本',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz` (`biz_id`),
    KEY `idx_user` (`user_id`, `create_time`),
    KEY `idx_create` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数日志表';

-- ---------- 5. 用户反馈 ----------
DROP TABLE IF EXISTS `data_query_feedback`;
CREATE TABLE IF NOT EXISTS `data_query_feedback` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `biz_id`        VARCHAR(64)  NOT NULL COMMENT '关联 data_query_log.biz_id',
    `user_id`       BIGINT       NOT NULL COMMENT '用户id',
    `rating`        TINYINT      NOT NULL DEFAULT 0 COMMENT '评分: 1 赞 / -1 踩 / 0 未评价',
    `correct_sql`   LONGTEXT     NULL COMMENT '用户纠正后的 SQL',
    `comment`       VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '文字反馈',
    `handle_status` VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '处理状态: PENDING/HANDLED/IGNORED',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz` (`biz_id`),
    KEY `idx_user` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问数反馈表';

-- ---------- 6. Few-shot 示例库 ----------
DROP TABLE IF EXISTS `data_fewshot`;
CREATE TABLE IF NOT EXISTS `data_fewshot` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`    TEXT         NOT NULL COMMENT '标准问题',
    `sql_text`    LONGTEXT     NOT NULL COMMENT '标准 SQL',
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

-- ---------- 7. 脱敏规则 ----------
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

-- ---------- 8. 问数目标表: 订单表 ----------
DROP TABLE IF EXISTS `biz_order`;
CREATE TABLE IF NOT EXISTS `biz_order` (
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`     VARCHAR(32)    NOT NULL COMMENT '订单编号',
    `user_name`    VARCHAR(64)    NOT NULL DEFAULT '' COMMENT '下单用户',
    `phone`        VARCHAR(20)    NOT NULL DEFAULT '' COMMENT '联系电话(演示用, 生产应脱敏)',
    `product_name` VARCHAR(128)   NOT NULL DEFAULT '' COMMENT '商品名称',
    `category`     VARCHAR(32)    NOT NULL DEFAULT '' COMMENT '商品类目',
    `amount`       DECIMAL(10,2)  NOT NULL DEFAULT 0.00 COMMENT '订单金额(元)',
    `quantity`     INT            NOT NULL DEFAULT 1 COMMENT '购买数量',
    `status`       TINYINT        NOT NULL DEFAULT 1 COMMENT '订单状态: 1=待付款 2=已付款 3=已发货 4=已完成 5=已取消',
    `pay_channel`  VARCHAR(16)    NOT NULL DEFAULT '' COMMENT '支付渠道: alipay/wechat/unionpay',
    `city`         VARCHAR(32)    NOT NULL DEFAULT '' COMMENT '下单城市',
    `created_at`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_category` (`category`),
    KEY `idx_city` (`city`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单表(问数演示目标表)';

-- 订单演示数据(覆盖近 3 个月, 多城市 / 多类目 / 多状态)
INSERT INTO `biz_order`
(`order_no`, `user_name`, `phone`, `product_name`, `category`, `amount`, `quantity`, `status`, `pay_channel`, `city`, `created_at`) VALUES
('NO20260801001', '张伟',   '13800138001', '无线蓝牙耳机',     '数码',   299.00,  1, 4, 'alipay',   '北京', '2026-08-01 10:12:33'),
('NO20260803002', '李娜',   '13900139002', '机械键盘',         '数码',   459.00,  1, 4, 'wechat',   '上海', '2026-08-03 14:22:10'),
('NO20260810003', '王强',   '13700137003', '纯棉T恤',          '服饰',   89.00,   2, 4, 'alipay',   '广州', '2026-08-10 09:05:47'),
('NO20260815004', '赵敏',   '13600136004', '保湿面霜',         '美妆',   199.00,  1, 3, 'wechat',   '深圳', '2026-08-15 16:40:21'),
('NO20260820005', '刘洋',   '13500135005', '跑步鞋',           '服饰',   599.00,  1, 2, 'unionpay', '北京', '2026-08-20 11:31:05'),
('NO20260825006', '陈静',   '13400134006', '智能手表',         '数码',   1299.00, 1, 4, 'alipay',   '杭州', '2026-08-25 20:18:54'),
('NO20260902007', '杨帆',   '13300133007', '咖啡机',           '家电',   899.00,  1, 4, 'wechat',   '上海', '2026-09-02 08:47:19'),
('NO20260905008', '孙丽',   '13200132008', '连衣裙',           '服饰',   259.00,  1, 1, 'alipay',   '广州', '2026-09-05 13:09:36'),
('NO20260910009', '周涛',   '13100131009', '空气净化器',       '家电',   1599.00, 1, 4, 'wechat',   '北京', '2026-09-10 15:52:48'),
('NO20260912010', '吴芳',   '13000130010', '口红套装',         '美妆',   129.00,  3, 4, 'alipay',   '成都', '2026-09-12 10:26:02'),
('NO20260918011', '郑浩',   '13800138011', '平板电脑',         '数码',   3299.00, 1, 3, 'unionpay', '上海', '2026-09-18 18:33:27'),
('NO20260922012', '冯雪',   '13900139012', '保暖内衣',         '服饰',   159.00,  2, 4, 'wechat',   '北京', '2026-09-22 09:14:59'),
('NO20260928013', '蒋华',   '13700137013', '电动牙刷',         '家电',   199.00,  2, 5, 'alipay',   '武汉', '2026-09-28 21:03:44'),
('NO20261001014', '沈燕',   '13600136014', '礼盒装茶叶',       '食品',   368.00,  1, 4, 'wechat',   '杭州', '2026-10-01 12:47:11'),
('NO20261003015', '韩磊',   '13500135015', '移动电源',         '数码',   149.00,  2, 4, 'alipay',   '深圳', '2026-10-03 16:20:38'),
('NO20261006016', '杨柳',   '13400134016', '粉底液',           '美妆',   219.00,  1, 2, 'wechat',   '上海', '2026-10-06 11:55:06'),
('NO20261009017', '朱军',   '13300133017', '双肩包',           '服饰',   329.00,  1, 4, 'unionpay', '北京', '2026-10-09 14:08:52'),
('NO20261012018', '秦琴',   '13200132018', '扫地机器人',       '家电',   2499.00, 1, 4, 'alipay',   '广州', '2026-10-12 19:36:20'),
('NO20261015019', '许文',   '13100131019', '坚果礼盒',         '食品',   188.00,  4, 4, 'wechat',   '成都', '2026-10-15 10:02:17'),
('NO20261018020', '何平',   '13000130020', '游戏手柄',         '数码',   399.00,  1, 3, 'alipay',   '武汉', '2026-10-18 22:44:09'),
('NO20261021021', '罗兰',   '13800138021', '保温杯',           '家居',   99.00,   3, 4, 'wechat',   '北京', '2026-10-21 08:27:43'),
('NO20261024022', '彭博',   '13900139022', '投影仪',           '数码',   2899.00, 1, 4, 'unionpay', '上海', '2026-10-24 15:19:31'),
('NO20261027023', '施婷',   '13700137023', '精华液',           '美妆',   459.00,  1, 2, 'alipay',   '深圳', '2026-10-27 13:41:58'),
('NO20261030024', '孔明',   '13600136024', '羽绒服',           '服饰',   899.00,  1, 4, 'wechat',   '北京', '2026-10-30 17:05:26'),
('NO20261102025', '杜鹃',   '13500135025', '电饭煲',           '家电',   599.00,  1, 4, 'alipay',   '杭州', '2026-11-02 10:38:14'),
('NO20261105026', '汪洋',   '13400134026', '巧克力礼盒',       '食品',   228.00,  2, 1, 'wechat',   '广州', '2026-11-05 19:22:47'),
('NO20261108027', '姚远',   '13300133027', '降噪耳机',         '数码',   1599.00, 1, 4, 'alipay',   '上海', '2026-11-08 09:49:03'),
('NO20261110028', '谭晶',   '13200134028', '针织围巾',         '服饰',   139.00,  2, 4, 'wechat',   '成都', '2026-11-10 14:16:35'),
('NO20261112029', '廖凯',   '13100131029', '除螨仪',           '家电',   499.00,  1, 5, 'unionpay', '武汉', '2026-11-12 20:54:21'),
('NO20261115030', '邹雨',   '13000130030', '香水',             '美妆',   689.00,  1, 4, 'alipay',   '深圳', '2026-11-15 11:31:58');

-- ---------- 9. 演示用术语与脱敏规则 ----------
INSERT INTO `data_term` (`term`, `aliases`, `definition`, `table_name`, `column_name`, `calc_rule`, `filter_rule`, `enabled`, `create_by`)
SELECT '销售额', '销售金额,营收', '已完成/已发货订单的金额合计', 'biz_order', 'amount', 'SUM(amount)', 'status IN (2,3,4)', 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM `data_term` WHERE `term` = '销售额');

INSERT INTO `data_term` (`term`, `aliases`, `definition`, `table_name`, `column_name`, `calc_rule`, `filter_rule`, `enabled`, `create_by`)
SELECT '订单量', '订单数,下单量', '所有订单的总笔数(含未付款)', 'biz_order', 'id', 'COUNT(1)', '', 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM `data_term` WHERE `term` = '订单量');

INSERT INTO `data_mask_rule` (`table_name`, `column_name`, `mask_type`, `expression`, `enabled`)
SELECT 'biz_order', 'phone', 'PHONE', '', 1
WHERE NOT EXISTS (SELECT 1 FROM `data_mask_rule` WHERE `table_name` = 'biz_order' AND `column_name` = 'phone');

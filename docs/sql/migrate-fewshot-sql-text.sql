-- 修复: data_fewshot 列名 `sql` 是 MySQL 8.0 保留字, 改为 sql_text
USE `lion_agent_4j`;

ALTER TABLE `data_fewshot`
    CHANGE COLUMN `sql` `sql_text` LONGTEXT NOT NULL COMMENT '标准 SQL';

SHOW COLUMNS FROM `data_fewshot`;

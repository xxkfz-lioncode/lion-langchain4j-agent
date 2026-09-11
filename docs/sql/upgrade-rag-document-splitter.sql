-- ============================================================
-- 旧库升级脚本: rag_document 表增加"文档切分方案"相关列
-- 触发场景: 查询/上传文档时报
--   java.sql.SQLSyntaxErrorException: Unknown column 'splitter_type' in 'field list'
-- 说明: 仅升级旧库使用; 全新初始化库直接执行 init.sql 即可(建表已包含这些列)
-- 重复执行会报 Duplicate column name, 属正常现象
-- ============================================================

USE `lion_agent_4j`;

ALTER TABLE `rag_document`
    ADD COLUMN `splitter_type`    VARCHAR(32)  NOT NULL DEFAULT 'recursive' COMMENT 'splitter type: recursive/paragraph/sentence/line/word/character/regex' AFTER `file_type`,
    ADD COLUMN `segment_size`     INT          NOT NULL DEFAULT 300 COMMENT 'segment size (chars)' AFTER `splitter_type`,
    ADD COLUMN `overlap_size`     INT          NOT NULL DEFAULT 50 COMMENT 'overlap size (chars)' AFTER `segment_size`,
    ADD COLUMN `splitter_pattern` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'regex pattern (only for regex splitter)' AFTER `overlap_size`;

SHOW COLUMNS FROM `rag_document`;

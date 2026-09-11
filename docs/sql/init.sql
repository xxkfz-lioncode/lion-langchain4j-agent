-- ============================================================
-- Lion LangChain4j Agent 初始化脚本(MySQL 8.x)
-- 执行方式: mysql -u root -p < init.sql 或使用 Navicat 等工具执行
-- ============================================================

CREATE DATABASE IF NOT EXISTS `lion_agent_4j`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `lion_agent_4j`;

-- 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码(BCrypt 密文; 若写入明文, 后端启动时自动升级加密)',
    `nickname`    VARCHAR(64)  DEFAULT '' COMMENT '昵称',
    `avatar`      VARCHAR(255) DEFAULT '' COMMENT '头像',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1 启用 / 0 禁用',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '角色: admin 超级管理员 / user 普通用户',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户表';

-- 默认账号: admin / 123456 (密码以明文写入, 后端启动时由 PasswordMigrationRunner 自动加密为 BCrypt)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`)
SELECT 'admin', '123456', '管理员', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username` = 'admin');

-- 历史遗留: 旧版"每个会话一行"的记忆表已废弃, 若曾执行过旧脚本先删掉
DROP TABLE IF EXISTS `chat_memory`;

-- 会话表(一个用户可建多个会话; 对应 chat_message.memory_id 形如 chat:{userId}:{conversationId})
-- 说明: 若为旧库(已建 chat_message), 只需执行本段新增该表即可, chat_message 无需改动
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE IF NOT EXISTS `chat_conversation` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话id',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户id',
    `title`       VARCHAR(128) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话表';

-- 对话消息明细表(每条消息一行, 便于按用户/角色查询历史对话)
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键(自增, 决定消息顺序)',
    `memory_id`    VARCHAR(128) NOT NULL COMMENT '会话记忆id(如 chat:1)',
    `user_id`      BIGINT       NULL COMMENT '用户id(冗余自 memory_id, 便于按用户查历史)',
    `role`         VARCHAR(16)  NOT NULL COMMENT '消息角色: system/user/assistant/tool',
    `content`      TEXT         NULL COMMENT '消息文本(展示用; 纯工具调用等可能为空)',
    `message_json` LONGTEXT     NOT NULL COMMENT 'LangChain4j 消息完整 JSON(用于还原消息对象)',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_memory` (`memory_id`, `id`),
    KEY `idx_user` (`user_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对话消息明细表';

-- 知识库文档元信息表(RAG 追加式入库后记录每个文档; 文档主键 id 同时作为向量片段的 docId 元数据,
-- 用于前端文件列表展示/按文件删除向量)
DROP TABLE IF EXISTS `rag_document`;
CREATE TABLE IF NOT EXISTS `rag_document` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键(同时作为向量片段 docId 元数据)',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '文件名(带扩展名)',
    `file_size`     BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    `file_type`     VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '文件类型(扩展名小写, 如 pdf/txt/md)',
    `splitter_type` VARCHAR(32)  NOT NULL DEFAULT 'recursive' COMMENT '切分方式: recursive/paragraph/sentence/line/word/character/regex',
    `segment_size`  INT          NOT NULL DEFAULT 300 COMMENT '切分块大小(字符)',
    `overlap_size`  INT          NOT NULL DEFAULT 50 COMMENT '相邻块重叠(字符)',
    `splitter_pattern` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '正则切分的正则(仅 regex 方式有值)',
    `file_path`     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '上传文件保存路径(相对运行目录, 如 upload/1_demo.pdf)',
    `segment_count` INT          NOT NULL DEFAULT 0 COMMENT '入库片段数(向量条数)',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1 成功 / 0 失败',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 知识库文档表';

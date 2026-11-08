package com.lion.agent.text2sql.util;

import java.util.regex.Pattern;

/**
 * SQL 归一化工具: 去注释、折叠空白、去尾部分号。
 * <p>
 * 用途: LLM 生成的 SQL 在进 SqlGuard 前统一格式, 避免注释绕过黑名单、
 * 空白差异导致缓存/日志比对失真。
 */
public final class SqlNormalizer {

    /** 单行注释 -- ... / # ... */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*|#[^\\n]*");
    /** 块注释 /* ... * /(非贪婪, 跨行) */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 连续空白折叠为单个空格 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlNormalizer() {
    }

    /**
     * 归一化 SQL。
     *
     * @param sql 原始 SQL
     * @return 去注释、折叠空白、去尾部分号的 SQL
     */
    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.trim();
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        // 去掉结尾分号(保留语句本身; 多语句由 SqlGuard 黑名单拦截)
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * 去掉反引号, 便于字段名比对。
     */
    public static String stripBackticks(String identifier) {
        if (identifier == null) {
            return "";
        }
        String s = identifier.trim();
        if (s.length() >= 2 && s.startsWith("`") && s.endsWith("`")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

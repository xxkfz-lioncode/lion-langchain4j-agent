package com.lion.agent.text2sql.service;

/**
 * SQL 安全护栏: 六层防线(见设计文档 §5.4)。
 * <pre>
 * Layer 1 语法黑名单 → Layer 2 AST 解析 → Layer 3 语义白名单
 *   → Layer 4 LIMIT 限额 → Layer 5 脱敏改写 → Layer 6 EXPLAIN 预检
 * </pre>
 * 任何 LLM 生成的 SQL 都必须经过本护栏校验后才能执行。
 */
public interface SqlGuard {

    /** 校验结果 */
    record GuardResult(boolean passed, String normalizedSql, String rejectReason) {

        public static GuardResult pass(String sql) {
            return new GuardResult(true, sql, "");
        }

        public static GuardResult reject(String reason) {
            return new GuardResult(false, null, reason);
        }
    }

    /**
     * 校验 SQL。
     *
     * @param sql    归一化后的 SQL
     * @param userId 用户id(用于行级权限, 骨架暂不启用)
     * @return 校验结果; {@code passed=true} 时 {@code normalizedSql} 为可执行 SQL
     */
    GuardResult check(String sql, Long userId);
}

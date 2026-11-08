package com.lion.agent.text2sql.service;

import java.util.List;

/**
 * 问数 SQL 执行器: 只读执行 + 性能预检 + 结果结构化。
 * <p>
 * 安全设计: 使用<b>独立只读数据源</b>(见 {@code ReadOnlyDataSourceConfig}),
 * 语句级超时 + 服务端 MAX_EXECUTION_TIME 双保险, 返回行数上限。
 */
public interface SqlExecutor {

    /** 执行查询 */
    QueryResult execute(String sql);

    /**
     * EXPLAIN 预检: 返回预计扫描行数。
     *
     * @return 预计扫描行数; 无法解析时返回 -1
     */
    long explainRows(String sql);

    /** 查询结果 */
    record QueryResult(List<ColumnMeta> columns,
                       List<List<Object>> rows,
                       int total,
                       long executeMs,
                       boolean truncated) {

        public static QueryResult empty() {
            return new QueryResult(List.of(), List.of(), 0, 0, false);
        }
    }

    /** 结果列描述 */
    record ColumnMeta(String name, String type) {
    }
}

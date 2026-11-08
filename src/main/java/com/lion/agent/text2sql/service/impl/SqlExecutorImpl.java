package com.lion.agent.text2sql.service.impl;

import com.lion.agent.text2sql.config.Text2SqlProperties;
import com.lion.agent.text2sql.service.SqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行器实现。
 * <p>
 * 执行流程(设计文档 §5.5):
 * <pre>
 * EXPLAIN 预检 → 拼装 MAX_EXECUTION_TIME Hint → 语句超时/行数上限 → 执行 → 结构化结果
 * </pre>
 */
@Slf4j
@Service
public class SqlExecutorImpl implements SqlExecutor {

    private final JdbcTemplate readOnlyJdbcTemplate;
    private final Text2SqlProperties props;

    public SqlExecutorImpl(@Qualifier("readOnlyJdbcTemplate") JdbcTemplate readOnlyJdbcTemplate,
                           Text2SqlProperties props) {
        this.readOnlyJdbcTemplate = readOnlyJdbcTemplate;
        this.props = props;
    }

    @Override
    public long explainRows(String sql) {
        if (!StringUtils.hasText(sql)) {
            return -1;
        }
        try {
            List<Map<String, Object>> rows = readOnlyJdbcTemplate.queryForList("EXPLAIN " + sql);
            long total = 0;
            for (Map<String, Object> row : rows) {
                Object r = row.get("rows");
                if (r instanceof Number n) {
                    total += n.longValue();
                } else if (r != null) {
                    try {
                        total += Long.parseLong(String.valueOf(r));
                    } catch (NumberFormatException ignore) {
                        // 非数字(如 null)跳过
                    }
                }
            }
            return total;
        } catch (Exception e) {
            log.warn("[text2sql] EXPLAIN 预检失败: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public QueryResult execute(String sql) {
        long start = System.currentTimeMillis();
        String safeSql = appendExecutionHint(sql);
        int maxRows = props.getMaxRows();

        try {
            return readOnlyJdbcTemplate.query(safeSql, (ResultSetExtractor<QueryResult>) rs -> {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<ColumnMeta> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(new ColumnMeta(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
                }

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                    // 防御: 理论上 JdbcTemplate.setMaxRows 已限制, 这里兜底
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                }
                return new QueryResult(columns, rows, rows.size(),
                        System.currentTimeMillis() - start, truncated);
            });
        } catch (Exception e) {
            log.error("[text2sql] SQL 执行失败: {}", e.getMessage());
            throw new SqlExecutionException(e.getMessage(), e);
        }
    }

    /**
     * 拼装服务端超时 Hint: {@code /*+ MAX_EXECUTION_TIME(ms) *\/} 仅对 SELECT 生效。
     * <p>
     * 注意: 即使客户端断开, 服务端也会终止查询, 避免慢查询堆积。
     */
    private String appendExecutionHint(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.regionMatches(true, 0, "select", 0, 6)) {
            return trimmed;
        }
        int timeoutMs = props.getQueryTimeoutSeconds() * 1000;
        return "/*+ MAX_EXECUTION_TIME(" + timeoutMs + ") */ " + trimmed;
    }

    /** 执行异常(区分超时/语法/权限等, 供上层做错误自愈) */
    public static class SqlExecutionException extends RuntimeException {
        public SqlExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

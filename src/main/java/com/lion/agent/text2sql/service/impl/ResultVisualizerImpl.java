package com.lion.agent.text2sql.service.impl;

import com.lion.agent.text2sql.service.ResultVisualizer;
import com.lion.agent.text2sql.service.SqlExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 图表类型推断实现。
 * <pre>
 * 空结果 → EMPTY
 * 单列单行 → METRIC
 * 单列多行 → TABLE
 * 两列: 首列日期 → LINE; 首列类别且 ≤8 行 → PIE; 否则 BAR
 * 多列: 首列日期 → LINE; 否则 BAR / TABLE
 * </pre>
 */
@Service
public class ResultVisualizerImpl implements ResultVisualizer {

    @Override
    public String inferChartType(List<SqlExecutor.ColumnMeta> columns, List<List<Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return EMPTY;
        }
        int colCount = columns.size();
        int rowCount = rows.size();

        if (colCount == 1) {
            return rowCount == 1 ? METRIC : TABLE;
        }

        String firstType = columns.get(0).type().toLowerCase(Locale.ROOT);
        boolean firstIsDate = firstType.contains("date") || firstType.contains("time")
                || firstType.contains("year");
        String firstCol = columns.get(0).name().toLowerCase(Locale.ROOT);
        boolean firstIsRatio = firstCol.contains("percent") || firstCol.contains("ratio")
                || firstCol.contains("占比");

        if (colCount == 2) {
            if (firstIsDate) {
                return LINE;
            }
            if (firstIsRatio && rowCount <= 8) {
                return PIE;
            }
            return rowCount <= 12 ? BAR : TABLE;
        }

        // ≥3 列
        if (firstIsDate) {
            return LINE;
        }
        if (rowCount <= 12 && colCount <= 5) {
            return BAR;
        }
        return TABLE;
    }
}

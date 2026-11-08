package com.lion.agent.text2sql.service;

import java.util.List;

/**
 * 结果可视化推断: 根据结果列与形状推断图表类型。
 * <p>
 * 规则见设计文档 §5.6.1。
 */
public interface ResultVisualizer {

    /** 支持的图表类型 */
    String TABLE = "TABLE";
    String LINE = "LINE";
    String BAR = "BAR";
    String PIE = "PIE";
    String METRIC = "METRIC";
    String EMPTY = "EMPTY";

    /**
     * 推断图表类型。
     *
     * @param columns 结果列
     * @param rows    结果行
     * @return 图表类型常量
     */
    String inferChartType(List<SqlExecutor.ColumnMeta> columns, List<List<Object>> rows);
}

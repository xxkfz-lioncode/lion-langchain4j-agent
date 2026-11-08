package com.lion.agent.text2sql.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 问数结果(同步返回体 / SSE result 事件的载荷)。
 */
@Data
public class Text2SqlResult {

    /** 本次问数唯一id */
    private String bizId;

    /** 用户原始问题(便于 SSE 前端直接渲染, 不必回查) */
    private String question;

    /** 生成的 SQL(校验 + 归一化 + 脱敏改写后) */
    private String sql;

    /** 口径说明(给用户看的) */
    private String explanation;

    /** 需要澄清时的问题(非空则本次未执行 SQL) */
    private String clarification;

    /** 识别出的意图 */
    private String intent;

    /** 结果列 */
    private List<ColumnMeta> columns;

    /** 结果行(二维数组, 与 columns 顺序对应) */
    private List<List<Object>> rows;

    /** 返回行数 */
    private Integer total;

    /** 执行耗时(毫秒) */
    private Long executeMs;

    /** 是否被截断(达到 maxRows) */
    private Boolean truncated;

    /** 推断的图表类型: TABLE/LINE/BAR/PIE/METRIC/EMPTY */
    private String chartType;

    /** 结果状态: SUCCESS/EMPTY/TIMEOUT/ERROR/CLARIFICATION */
    private String status;

    /** 错误信息(status=ERROR 时) */
    private String errorMessage;

    /** 结果列描述 */
    @Data
    public static class ColumnMeta {
        private String name;
        private String type;

        public ColumnMeta() {
        }

        public ColumnMeta(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }
}

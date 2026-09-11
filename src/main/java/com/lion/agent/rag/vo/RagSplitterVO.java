package com.lion.agent.rag.vo;

import lombok.Data;

/**
 * 可选的文档切分方式(前端下拉框数据源, 由 {@code GET /api/rag/splitters} 返回)
 */
@Data
public class RagSplitterVO {

    /** 切分方式编码(上传时原样回传) */
    private String code;

    /** 展示名, 如 "递归切分(推荐)" */
    private String label;

    /** 短名(文件列表标签用), 如 "递归" */
    private String shortLabel;

    /** 说明文案 */
    private String description;

    /** 默认块大小(字符), 前端切到该方式时回填 */
    private Integer defaultSegmentSize;

    /** 默认重叠(字符) */
    private Integer defaultOverlap;

    /** 是否支持自定义正则 */
    private Boolean patternSupported;

    /** 默认正则(patternSupported=true 时作为输入框占位) */
    private String defaultPattern;

    /** 正则填写提示 */
    private String patternHint;

    /** 块大小下限(前端输入框约束) */
    private Integer minSegmentSize;

    /** 块大小上限 */
    private Integer maxSegmentSize;
}

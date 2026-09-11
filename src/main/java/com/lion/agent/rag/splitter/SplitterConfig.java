package com.lion.agent.rag.splitter;

/**
 * 切分参数(上传接口入参): 字段可为空, 为空时取所选切分方式的默认值。
 *
 * @param type        切分方式编码, 见 {@link SplitterType}(为空用默认方式)
 * @param segmentSize 单块最大字符数(为空用该方式默认值)
 * @param overlap     相邻块重叠字符数(为空用该方式默认值)
 * @param pattern     自定义正则(仅 regex 方式使用, 为空用该方式默认正则)
 */
public record SplitterConfig(String type, Integer segmentSize, Integer overlap, String pattern) {

    public static SplitterConfig of(String type, Integer segmentSize, Integer overlap, String pattern) {
        return new SplitterConfig(type, segmentSize, overlap, pattern);
    }

    /** 全默认(递归切分 + 默认块大小/重叠) */
    public static SplitterConfig defaults() {
        return new SplitterConfig(null, null, null, null);
    }
}

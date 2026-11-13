package com.lion.agent.ai.rag.util;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.Set;

/**
 * RAG 模块通用工具(文件类型/文件名清洗/异常根因/摘要截断)
 * <p>
 * 通用能力优先复用 Hutool(FileNameUtil/ExceptionUtil/StrUtil), 不重复造轮子;
 * 只保留 RAG 语义的薄封装(扩展名→存储类型、根因消息兜底等)。
 */
public final class RagUtils {

    private RagUtils() {
    }

    /** 支持的知识文档扩展名(Hutool FileNameUtil.extName 返回不带点的扩展名) */
    private static final Set<String> SUPPORTED_EXTS = Set.of("pdf", "txt", "md", "markdown");

    /**
     * 是否为支持的知识文档格式(.pdf/.txt/.md/.markdown)
     */
    public static boolean isSupported(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return false;
        }
        return SUPPORTED_EXTS.contains(FileNameUtil.extName(fileName).toLowerCase(Locale.ROOT));
    }

    /**
     * 按文件名取存储类型标识(pdf→pdf, md/markdown→md, 其余→txt)
     * <p>
     * 该类型同时决定解析器选择(pdf 走 ApachePdfBoxDocumentParser)与向量元数据展示。
     */
    public static String typeOf(String fileName) {
        String ext = FileNameUtil.extName(StrUtil.nullToEmpty(fileName)).toLowerCase(Locale.ROOT);
        if ("pdf".equals(ext)) {
            return "pdf";
        }
        if ("md".equals(ext) || "markdown".equals(ext)) {
            return "md";
        }
        return "txt";
    }

    /**
     * 清洗文件名: 去掉路径部分(防目录穿越)与非法字符(防对象Key非法)
     * <p>
     * 路径剥离用 Hutool FileNameUtil.getName(兼容 win/linux 分隔符), 非法字符替换为下划线。
     */
    public static String sanitizeFileName(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "unnamed";
        }
        String cleaned = FileNameUtil.getName(fileName).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    /**
     * 取异常根因消息(依赖 SDK 的异常链较深, 直接 getMessage 常为 null):
     * Hutool ExceptionUtil 取根因, 消息为空时兜底为异常类名
     */
    public static String rootMessage(Throwable e) {
        Throwable root = e == null ? new IllegalStateException("未知错误") : ExceptionUtil.getRootCause(e);
        String msg = root.getMessage();
        return StrUtil.isBlank(msg) ? root.getClass().getSimpleName() : msg;
    }

    /**
     * 摘要截断: 超过 maxLen 时以 … 结尾(引用来源列表展示用, 全文另存)
     */
    public static String snippet(String text, int maxLen) {
        if (StrUtil.isEmpty(text) || text.length() <= maxLen) {
            return text;
        }
        return StrUtil.subPre(text, maxLen) + "…";
    }
}

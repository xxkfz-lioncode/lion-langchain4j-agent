package com.lion.agent.text2sql.service;

import com.lion.agent.text2sql.pojo.vo.SchemaColumnVO;
import com.lion.agent.text2sql.pojo.vo.SchemaTableVO;

import java.util.Collection;
import java.util.List;

/**
 * 元数据与语义层: 扫描数据库 → 落元数据 → (LLM 增强) → 供 SchemaLinker 检索。
 * <p>
 * 这是问数准确率的<b>地基</b>: 裸表结构直接喂给 LLM, 模型不知道
 * {@code t_order.status} 的 1/2/3/4/5 分别代表什么, 也不知道"销售额"该用哪张表。
 */
public interface SchemaManager {

    /** JOIN 路径(外键关系) */
    record JoinPath(String fromTable, String fromColumn, String toTable, String toColumn) {
    }

    /**
     * 全量刷新: 重新扫描目标库并覆盖元数据。
     * <p>
     * 触发时机: 启动时(元数据为空)、每日定时、手动刷新接口。
     *
     * @return 本次刷新的 schema 版本号(yyyyMMddHHmmss)
     */
    String fullRefresh();

    /** 增量刷新(骨架预留, 当前等价于全量刷新) */
    default String incrementalRefresh() {
        return fullRefresh();
    }

    /**
     * 构造指定表的 schema 摘要(注入 LLM prompt)。
     *
     * @param tableNames 表名集合
     * @return 精简的 schema digest 文本
     */
    String buildSchemaDigest(Collection<String> tableNames);

    /** 表列表(支持按关键字过滤, 仅返回允许查询的表) */
    List<SchemaTableVO> listTables(String keyword);

    /** 指定表的字段列表 */
    List<SchemaColumnVO> listColumns(String tableName);

    /** 当前元数据版本 */
    String currentVersion();

    /** 表是否允许问数查询 */
    boolean isQueryableTable(String tableName);

    /** 字段是否存在于给定表中 */
    boolean existsColumn(String columnName, Collection<String> tables);

    /** 根据外键推断 JOIN 路径 */
    List<JoinPath> inferJoinPaths(String fromTable, String toTable);
}

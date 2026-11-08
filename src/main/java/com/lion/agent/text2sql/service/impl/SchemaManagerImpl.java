package com.lion.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.text2sql.config.Text2SqlProperties;
import com.lion.agent.text2sql.entity.DataSchemaColumnEntity;
import com.lion.agent.text2sql.entity.DataSchemaTableEntity;
import com.lion.agent.text2sql.mapper.DataSchemaColumnMapper;
import com.lion.agent.text2sql.mapper.DataSchemaTableMapper;
import com.lion.agent.text2sql.pojo.vo.SchemaColumnVO;
import com.lion.agent.text2sql.pojo.vo.SchemaTableVO;
import com.lion.agent.text2sql.service.SchemaManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 元数据与语义层实现。
 * <p>
 * 扫描流程: {@code information_schema} → 过滤(前缀/视图/系统表) → 批量 UPSERT →
 * (可选) LLM 字段增强。骨架阶段先保证"扫得到、查得到、digest 拼得出"。
 */
@Slf4j
@Service
public class SchemaManagerImpl implements SchemaManager {

    private static final DateTimeFormatter VERSION_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 采样值上限(小表才采样, 避免大表全表扫描) */
    private static final long SAMPLE_MAX_ROWS = 100_000L;

    private final JdbcTemplate jdbcTemplate;
    private final DataSchemaTableMapper tableMapper;
    private final DataSchemaColumnMapper columnMapper;
    private final Text2SqlProperties props;

    public SchemaManagerImpl(JdbcTemplate jdbcTemplate,
                             DataSchemaTableMapper tableMapper,
                             DataSchemaColumnMapper columnMapper,
                             Text2SqlProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.props = props;
    }

    /**
     * 启动时: 元数据为空则全量刷新(不阻塞启动, 失败仅告警)。
     */
    @PostConstruct
    public void init() {
        try {
            Long count = tableMapper.selectCount(new LambdaQueryWrapper<DataSchemaTableEntity>()
                    .eq(DataSchemaTableEntity::getIsQueryable, 1));
            if (count == null || count == 0) {
                log.info("[text2sql] 元数据为空, 触发首次全量刷新");
                fullRefresh();
            } else {
                log.info("[text2sql] 已有元数据 {} 张可查询表, 跳过首次刷新", count);
            }
        } catch (Exception e) {
            log.warn("[text2sql] 启动时元数据初始化失败(不阻塞启动): {}", e.getMessage());
        }
    }

    /**
     * 每日定时刷新。
     */
    @Scheduled(cron = "${lion.text2sql.schema-refresh-cron:0 0 2 * * ?}")
    public void scheduledRefresh() {
        log.info("[text2sql] 定时全量刷新元数据");
        fullRefresh();
    }

    @Override
    public String fullRefresh() {
        String version = LocalDateTime.now().format(VERSION_FMT);
        String database = props.getDatabase();
        String prefix = props.getScanIncludePrefix();

        // 1. 扫描表
        String tableSql = "SELECT table_name, table_comment, table_type, table_rows "
                + "FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'";
        List<Object> tableArgs = new ArrayList<>();
        tableArgs.add(database);
        if (StringUtils.hasText(prefix)) {
            tableSql += " AND table_name LIKE ?";
            tableArgs.add(prefix + "%");
        }
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql, tableArgs.toArray());
        log.info("[text2sql] 扫描到 {} 张表(库={}, 前缀={})", tables.size(), database, prefix);

        // 2. 逐表落库
        int tableCount = 0;
        int columnCount = 0;
        for (Map<String, Object> t : tables) {
            String tableName = String.valueOf(t.get("table_name"));
            String comment = toStr(t.get("table_comment"));
            Long rows = toLong(t.get("table_rows"));

            DataSchemaTableEntity entity = new DataSchemaTableEntity();
            entity.setTableName(tableName);
            entity.setTableComment(comment);
            entity.setTableType(toStr(t.get("table_type")));
            entity.setRowCount(rows);
            entity.setIsQueryable(1);
            entity.setSchemaVersion(version);
            upsertTable(entity);
            tableCount++;

            // 3. 扫描字段
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, column_type, column_comment, is_nullable, column_key "
                            + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                            + "ORDER BY ordinal_position",
                    database, tableName);
            for (Map<String, Object> c : columns) {
                DataSchemaColumnEntity col = new DataSchemaColumnEntity();
                col.setTableName(tableName);
                col.setColumnName(toStr(c.get("column_name")));
                col.setDataType(toStr(c.get("column_type")));
                col.setColumnComment(toStr(c.get("column_comment")));
                col.setIsNullable(toStr(c.get("is_nullable")));
                col.setColumnKey(toStr(c.get("column_key")));
                col.setIsFk(0);
                col.setRefTable("");
                col.setRefColumn("");
                col.setValueDomain("");
                col.setSampleValues("");
                // 敏感字段识别: 命名包含 phone/mobile/idcard/email/password
                col.setIsSensitive(isSensitiveColumn(col.getColumnName()) ? 1 : 0);
                col.setSchemaVersion(version);
                col.setLlmDescription("");
                // 小表采样值域(大表跳过, 避免全表扫描)
                if (rows != null && rows <= SAMPLE_MAX_ROWS) {
                    col.setSampleValues(sampleValues(database, tableName, col.getColumnName()));
                }
                upsertColumn(col);
                columnCount++;
            }
        }

        // 4. 外键关系回填
        backfillForeignKeys(database, version);

        // 5. LLM 字段增强(注释缺失的字段)
        if (props.isLlmColumnEnhance()) {
            try {
                enhanceColumnsWithLlm(version);
            } catch (Exception e) {
                log.warn("[text2sql] LLM 字段增强失败(不阻塞刷新): {}", e.getMessage());
            }
        }

        log.info("[text2sql] 刷新完成: {} 张表 / {} 个字段, version={}", tableCount, columnCount, version);
        return version;
    }

    @Override
    public String buildSchemaDigest(Collection<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String tableName : tableNames) {
            DataSchemaTableEntity table = tableMapper.selectOne(new LambdaQueryWrapper<DataSchemaTableEntity>()
                    .eq(DataSchemaTableEntity::getTableName, tableName).last("LIMIT 1"));
            if (table == null) {
                continue;
            }
            sb.append("【表】").append(tableName)
                    .append(StringUtils.hasText(table.getTableComment()) ? "（" + table.getTableComment() + "）" : "")
                    .append(" 约 ").append(table.getRowCount() == null ? 0 : table.getRowCount()).append(" 行\n");

            List<DataSchemaColumnEntity> columns = columnMapper.selectList(
                    new LambdaQueryWrapper<DataSchemaColumnEntity>()
                            .eq(DataSchemaColumnEntity::getTableName, tableName)
                            .orderByAsc(DataSchemaColumnEntity::getId));
            for (DataSchemaColumnEntity c : columns) {
                sb.append("  - ").append(c.getColumnName())
                        .append("(").append(c.getDataType()).append(")");
                String desc = StringUtils.hasText(c.getLlmDescription())
                        ? c.getLlmDescription() : c.getColumnComment();
                if (StringUtils.hasText(desc)) {
                    sb.append("：").append(desc);
                }
                if (StringUtils.hasText(c.getValueDomain())) {
                    sb.append("，值域：").append(c.getValueDomain());
                }
                if ("PRI".equalsIgnoreCase(c.getColumnKey())) {
                    sb.append("，主键");
                }
                if (c.getIsFk() != null && c.getIsFk() == 1 && StringUtils.hasText(c.getRefTable())) {
                    sb.append("，外键→").append(c.getRefTable()).append(".").append(c.getRefColumn());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<SchemaTableVO> listTables(String keyword) {
        LambdaQueryWrapper<DataSchemaTableEntity> wrapper = new LambdaQueryWrapper<DataSchemaTableEntity>()
                .eq(DataSchemaTableEntity::getIsQueryable, 1)
                .orderByDesc(DataSchemaTableEntity::getRowCount);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(DataSchemaTableEntity::getTableName, keyword)
                    .or().like(DataSchemaTableEntity::getTableComment, keyword));
        }
        List<DataSchemaTableEntity> list = tableMapper.selectList(wrapper);
        List<SchemaTableVO> result = new ArrayList<>();
        for (DataSchemaTableEntity e : list) {
            SchemaTableVO vo = new SchemaTableVO();
            vo.setId(e.getId());
            vo.setTableName(e.getTableName());
            vo.setTableComment(e.getTableComment());
            vo.setRowCount(e.getRowCount());
            vo.setQueryable(e.getIsQueryable() != null && e.getIsQueryable() == 1);
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<SchemaColumnVO> listColumns(String tableName) {
        List<DataSchemaColumnEntity> list = columnMapper.selectList(
                new LambdaQueryWrapper<DataSchemaColumnEntity>()
                        .eq(DataSchemaColumnEntity::getTableName, tableName)
                        .orderByAsc(DataSchemaColumnEntity::getId));
        List<SchemaColumnVO> result = new ArrayList<>();
        for (DataSchemaColumnEntity e : list) {
            SchemaColumnVO vo = new SchemaColumnVO();
            vo.setId(e.getId());
            vo.setTableName(e.getTableName());
            vo.setColumnName(e.getColumnName());
            vo.setDataType(e.getDataType());
            vo.setColumnComment(e.getColumnComment());
            vo.setLlmDescription(e.getLlmDescription());
            vo.setValueDomain(e.getValueDomain());
            vo.setSampleValues(e.getSampleValues());
            vo.setColumnKey(e.getColumnKey());
            vo.setSensitive(e.getIsSensitive() != null && e.getIsSensitive() == 1);
            result.add(vo);
        }
        return result;
    }

    @Override
    public String currentVersion() {
        DataSchemaTableEntity latest = tableMapper.selectOne(new LambdaQueryWrapper<DataSchemaTableEntity>()
                .orderByDesc(DataSchemaTableEntity::getUpdateTime).last("LIMIT 1"));
        return latest == null ? "" : latest.getSchemaVersion();
    }

    @Override
    public boolean isQueryableTable(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return false;
        }
        DataSchemaTableEntity entity = tableMapper.selectOne(new LambdaQueryWrapper<DataSchemaTableEntity>()
                .eq(DataSchemaTableEntity::getTableName, tableName).last("LIMIT 1"));
        return entity != null && entity.getIsQueryable() != null && entity.getIsQueryable() == 1;
    }

    @Override
    public boolean existsColumn(String columnName, Collection<String> tables) {
        if (!StringUtils.hasText(columnName) || tables == null || tables.isEmpty()) {
            return false;
        }
        Long count = columnMapper.selectCount(new LambdaQueryWrapper<DataSchemaColumnEntity>()
                .eq(DataSchemaColumnEntity::getColumnName, columnName)
                .in(DataSchemaColumnEntity::getTableName, tables));
        return count != null && count > 0;
    }

    @Override
    public List<JoinPath> inferJoinPaths(String fromTable, String toTable) {
        List<DataSchemaColumnEntity> fks = columnMapper.selectList(
                new LambdaQueryWrapper<DataSchemaColumnEntity>()
                        .eq(DataSchemaColumnEntity::getIsFk, 1)
                        .eq(DataSchemaColumnEntity::getRefTable, toTable)
                        .eq(DataSchemaColumnEntity::getTableName, fromTable));
        List<JoinPath> paths = new ArrayList<>();
        for (DataSchemaColumnEntity fk : fks) {
            paths.add(new JoinPath(fromTable, fk.getColumnName(), toTable, fk.getRefColumn()));
        }
        return paths;
    }

    // ==================== 内部方法 ====================

    private void upsertTable(DataSchemaTableEntity entity) {
        DataSchemaTableEntity exist = tableMapper.selectOne(new LambdaQueryWrapper<DataSchemaTableEntity>()
                .eq(DataSchemaTableEntity::getTableName, entity.getTableName()).last("LIMIT 1"));
        if (exist == null) {
            tableMapper.insert(entity);
        } else {
            entity.setId(exist.getId());
            tableMapper.updateById(entity);
        }
    }

    private void upsertColumn(DataSchemaColumnEntity entity) {
        DataSchemaColumnEntity exist = columnMapper.selectOne(new LambdaQueryWrapper<DataSchemaColumnEntity>()
                .eq(DataSchemaColumnEntity::getTableName, entity.getTableName())
                .eq(DataSchemaColumnEntity::getColumnName, entity.getColumnName())
                .last("LIMIT 1"));
        if (exist == null) {
            columnMapper.insert(entity);
        } else {
            entity.setId(exist.getId());
            columnMapper.updateById(entity);
        }
    }

    private void backfillForeignKeys(String database, String version) {
        List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                "SELECT kcu.table_name, kcu.column_name, kcu.referenced_table_name, kcu.referenced_column_name "
                        + "FROM information_schema.key_column_usage kcu "
                        + "WHERE kcu.table_schema = ? AND kcu.referenced_table_name IS NOT NULL",
                database);
        for (Map<String, Object> fk : fks) {
            String tn = toStr(fk.get("table_name"));
            String cn = toStr(fk.get("column_name"));
            DataSchemaColumnEntity exist = columnMapper.selectOne(new LambdaQueryWrapper<DataSchemaColumnEntity>()
                    .eq(DataSchemaColumnEntity::getTableName, tn)
                    .eq(DataSchemaColumnEntity::getColumnName, cn)
                    .last("LIMIT 1"));
            if (exist != null) {
                exist.setIsFk(1);
                exist.setRefTable(toStr(fk.get("referenced_table_name")));
                exist.setRefColumn(toStr(fk.get("referenced_column_name")));
                exist.setSchemaVersion(version);
                columnMapper.updateById(exist);
            }
        }
    }

    private String sampleValues(String database, String tableName, String columnName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT `" + columnName + "` AS v FROM `" + database + "`.`" + tableName
                            + "` WHERE `" + columnName + "` IS NOT NULL LIMIT 5");
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(row.get("v"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 敏感字段识别: 按命名约定。
     */
    private boolean isSensitiveColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        String c = columnName.toLowerCase();
        return c.contains("password") || c.contains("phone") || c.contains("mobile")
                || c.contains("idcard") || c.contains("id_card") || c.contains("email")
                || c.contains("bank") || c.contains("salary");
    }

    /**
     * LLM 字段增强: 对注释为空的字段批量生成业务含义与值域说明。
     * <p>
     * 骨架阶段为简化实现, 仅做单批调用 + 失败降级; 生产建议分批 + 异步 + 缓存。
     */
    private void enhanceColumnsWithLlm(String version) {
        // 骨架占位: 具体增强逻辑后续完善(见设计文档 §5.1.2)
        // 触发条件: data_schema_column.column_comment 为空 且 llm_description 为空
    }

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

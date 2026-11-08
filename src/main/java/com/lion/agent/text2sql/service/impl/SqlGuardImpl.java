package com.lion.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.text2sql.config.Text2SqlProperties;
import com.lion.agent.text2sql.entity.DataMaskRuleEntity;
import com.lion.agent.text2sql.mapper.DataMaskRuleMapper;
import com.lion.agent.text2sql.service.SchemaManager;
import com.lion.agent.text2sql.service.SqlExecutor;
import com.lion.agent.text2sql.service.SqlGuard;
import com.lion.agent.text2sql.util.SqlNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全护栏实现。
 * <p>
 * 骨架阶段实现 Layer 1/2/3/4/6; Layer 5 脱敏改写实现了常见 5 种类型。
 */
@Slf4j
@Service
public class SqlGuardImpl implements SqlGuard {

    // ==================== Layer 1: 语法黑名单 ====================

    /** 危险关键字 / 注释 / 多语句 / 文件读写 / 时间盲注 / 系统库 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)("
                    + "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|lock|unlock"
                    // 注意: 不能加 "desc" —— ORDER BY x DESC 的降序关键字会被误伤;
                    // "DESC table" 元数据命令由 Layer 2 仅允许 SELECT 兜底拦截, describe 保留在此
                    + "|handler|call|prepare|execute|set|reset|flush|kill|shutdown|use|describe|show"
                    // "replace" 同理: REPLACE(str,from,to) 是合法函数, REPLACE INTO 写语句由 Layer 2 兜底
                    + "|merge|rename|comment|analyze|optimize|repair)\\b"
                    + "|(--|#|/\\*)"                        // 注释
                    + "|(;\\s*\\S)"                        // 分号后还有内容(多语句)
                    + "|(into\\s+(out|dump)file)"           // 写文件
                    + "|(load_file|load\\s+data)"           // 读文件
                    + "|(sleep|benchmark|pg_sleep)"         // 时间盲注
                    + "|(information_schema|mysql\\.|performance_schema|sys\\.)" // 系统库
                    + ")"
    );

    /** 危险函数(白名单之外单独再拦一层) */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "LOAD_FILE", "SLEEP", "BENCHMARK", "PG_SLEEP", "GET_LOCK", "RELEASE_LOCK",
            "UUID_SHORT", "SYS_GUID", "EXP", "UTL_INADDR", "DBMS_PIPE"
    );

    private final SchemaManager schemaManager;
    private final SqlExecutor sqlExecutor;
    private final DataMaskRuleMapper maskRuleMapper;
    private final Text2SqlProperties props;

    public SqlGuardImpl(SchemaManager schemaManager,
                        SqlExecutor sqlExecutor,
                        DataMaskRuleMapper maskRuleMapper,
                        Text2SqlProperties props) {
        this.schemaManager = schemaManager;
        this.sqlExecutor = sqlExecutor;
        this.maskRuleMapper = maskRuleMapper;
        this.props = props;
    }

    @Override
    public GuardResult check(String sql, Long userId) {
        String normalized = SqlNormalizer.normalize(sql);

        // Layer 1: 语法黑名单
        if (DANGEROUS.matcher(normalized).find()) {
            return reject("SQL 包含不允许的关键字/注释/多语句", normalized);
        }

        // Layer 2: AST 解析
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(normalized);
        } catch (Exception e) {
            return reject("SQL 语法解析失败: " + e.getMessage(), normalized);
        }
        if (!(statement instanceof Select selectStatement)) {
            return reject("仅允许 SELECT 查询", normalized);
        }
        if (!(selectStatement.getSelectBody() instanceof PlainSelect select)) {
            return reject("仅支持简单 SELECT(暂不支持 UNION/ WITH 等)", normalized);
        }

        // Layer 3: 语义白名单
        GuardResult semantic = checkSemantic(select, normalized);
        if (!semantic.passed()) {
            return semantic;
        }

        // Layer 4: LIMIT 限额
        String limited = enforceLimit(select, normalized);

        // Layer 5: 脱敏改写
        String masked = applyMasking(limited, select);

        // Layer 6: EXPLAIN 预检
        long explainRows = sqlExecutor.explainRows(masked);
        if (explainRows > props.getMaxExplainRows()) {
            return reject("预计扫描 " + explainRows + " 行, 超过上限 " + props.getMaxExplainRows()
                    + ", 请增加筛选条件", masked);
        }

        return GuardResult.pass(masked);
    }

    private GuardResult reject(String reason, String sql) {
        log.warn("[text2sql] SQL 被拒绝: {} | sql={}", reason, sql);
        return GuardResult.reject(reason);
    }

    // ==================== Layer 3: 语义校验 ====================

    private GuardResult checkSemantic(PlainSelect select, String sql) {
        // 3.1 表白名单
        // PlainSelect 同时实现 Statement 与 Expression, 必须显式消歧
        List<String> tables = new TablesNamesFinder<Void>().getTableList((Statement) select);
        if (tables.isEmpty()) {
            return reject("SQL 未包含任何表", sql);
        }
        List<String> invalid = new ArrayList<>();
        for (String t : tables) {
            if (!schemaManager.isQueryableTable(t)) {
                invalid.add(t);
            }
        }
        if (!invalid.isEmpty()) {
            return reject("表不存在或不允许查询: " + String.join(", ", invalid), sql);
        }

        // 3.2 字段存在性
        Set<String> referenced = new HashSet<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            collectColumns(item.getExpression(), referenced);
        }
        if (select.getWhere() != null) {
            collectColumns(select.getWhere(), referenced);
        }
        if (select.getHaving() != null) {
            collectColumns(select.getHaving(), referenced);
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectColumns(join.getOnExpression(), referenced);
            }
        }
        for (String col : referenced) {
            if (!schemaManager.existsColumn(col, tables)) {
                return reject("字段不存在: " + col, sql);
            }
        }

        // 3.3 禁止 SELECT *
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof AllColumns || expr instanceof AllTableColumns) {
                return reject("禁止 SELECT *, 请显式指定字段", sql);
            }
        }

        // 3.4 危险函数
        for (String fn : collectFunctions(select)) {
            if (FORBIDDEN_FUNCTIONS.contains(fn.toUpperCase())) {
                return reject("包含不允许的函数: " + fn, sql);
            }
        }

        // 3.5 JOIN 数量
        int joinCount = select.getJoins() == null ? 0 : select.getJoins().size();
        if (joinCount > props.getMaxJoins()) {
            return reject("JOIN 过多(最多 " + props.getMaxJoins() + " 个)", sql);
        }

        return GuardResult.pass(sql);
    }

    // ==================== Layer 4: LIMIT ====================

    private String enforceLimit(PlainSelect select, String sql) {
        Limit limit = select.getLimit();
        if (limit == null) {
            return sql + " LIMIT " + props.getDefaultLimit();
        }
        Expression rowCount = limit.getRowCount();
        if (rowCount instanceof LongValue lv) {
            long n = lv.getValue();
            if (n > props.getMaxRows()) {
                limit.setRowCount(new LongValue(props.getMaxRows()));
                return select.toString();
            }
        }
        return sql;
    }

    // ==================== Layer 5: 脱敏改写 ====================

    private String applyMasking(String sql, PlainSelect select) {
        List<DataMaskRuleEntity> rules = maskRuleMapper.selectList(
                new LambdaQueryWrapper<DataMaskRuleEntity>().eq(DataMaskRuleEntity::getEnabled, 1));
        if (rules.isEmpty()) {
            return sql;
        }
        // 查询涉及的真实表(FROM/JOIN): 单表查询时 LLM 生成的列通常不带表前缀,
        // column.getTable() 为 null, 必须从 FROM 补位, 否则规则永远匹配不上
        List<String> queryTables = new ArrayList<>(
                new TablesNamesFinder<Void>().getTableList((Statement) select));
        List<SelectItem<?>> items = new ArrayList<>(select.getSelectItems());
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            if (!(item.getExpression() instanceof Column column)) {
                continue;
            }
            String colName = column.getColumnName();
            String qualifier = column.getTable() == null ? null : column.getTable().getName();
            for (DataMaskRuleEntity rule : rules) {
                if (matchesRule(rule, qualifier, colName, queryTables)) {
                    Function maskFn = buildMaskFunction(rule.getMaskType(), rule.getExpression(), colName);
                    if (maskFn != null) {
                        // 保留原列名作为别名, 前端展示不变
                        items.set(i, SelectItem.from(maskFn, new Alias(colName)));
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            select.setSelectItems(items);
            return select.toString();
        }
        return sql;
    }

    private boolean matchesRule(DataMaskRuleEntity rule, String qualifier,
                                String columnName, List<String> queryTables) {
        // 列带限定符(biz_order.phone): 直接对限定符匹配
        if (StringUtils.hasText(qualifier)) {
            return tableMatches(rule, qualifier) && columnMatches(rule, columnName);
        }
        // 不带限定符: 列属于 FROM 的真实表。
        // 单表直接用该表; JOIN 场景无法静态确定归属, 任一相关表命中规则就脱敏(宁可多脱不漏脱)
        boolean tableHit = queryTables.stream().anyMatch(t -> tableMatches(rule, t));
        return tableHit && columnMatches(rule, columnName);
    }

    private boolean tableMatches(DataMaskRuleEntity rule, String tableName) {
        return "*".equals(rule.getTableName()) || tableName.equalsIgnoreCase(rule.getTableName());
    }

    private boolean columnMatches(DataMaskRuleEntity rule, String columnName) {
        return "*".equals(rule.getColumnName()) || columnName.equalsIgnoreCase(rule.getColumnName());
    }

    /**
     * 构造脱敏函数: CONCAT(LEFT(col,3),'****',RIGHT(col,4)) 等。
     */
    private Function buildMaskFunction(String maskType, String customExpr, String columnName) {
        Column col = new Column(columnName);
        return switch (maskType == null ? "" : maskType.toUpperCase()) {
            case "PHONE", "MOBILE" -> concat(
                    function("LEFT", col, new LongValue(3)),
                    new StringValue("****"),
                    function("RIGHT", col, new LongValue(4)));
            case "ID_CARD" -> concat(
                    function("LEFT", col, new LongValue(6)),
                    new StringValue("********"),
                    function("RIGHT", col, new LongValue(4)));
            case "BANK_CARD" -> concat(
                    function("LEFT", col, new LongValue(4)),
                    new StringValue("****"),
                    function("RIGHT", col, new LongValue(4)));
            case "EMAIL" -> concat(
                    function("LEFT", col, new LongValue(2)),
                    new StringValue("***@"),
                    function("SUBSTRING_INDEX", col, new StringValue("@"), new LongValue(-1)));
            case "NAME" -> concat(
                    function("LEFT", col, new LongValue(1)),
                    new StringValue("**"));
            case "CUSTOM" -> {
                if (!StringUtils.hasText(customExpr)) {
                    yield null;
                }
                // CUSTOM 表达式为 SQL 片段(含 {col} 占位), 骨架阶段仅支持直接解析
                try {
                    yield (Function) CCJSqlParserUtil.parseCondExpression(
                            customExpr.replace("{col}", columnName));
                } catch (Exception e) {
                    log.warn("[text2sql] 脱敏表达式解析失败: {}", customExpr);
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static Function function(String name, Expression... args) {
        return new Function(name, args);
    }

    private static Function concat(Expression... args) {
        return function("CONCAT", args);
    }

    // ==================== 工具: 表达式遍历 ====================

    private void collectColumns(Expression expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        expr.accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S s) {
                out.add(column.getColumnName());
                return super.visit(column, s);
            }
        }, null);
    }

    private Set<String> collectFunctions(PlainSelect select) {
        Set<String> names = new HashSet<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            collectFunctionNames(item.getExpression(), names);
        }
        if (select.getWhere() != null) {
            collectFunctionNames(select.getWhere(), names);
        }
        return names;
    }

    private void collectFunctionNames(Expression expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        expr.accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Function function, S s) {
                out.add(function.getName());
                return super.visit(function, s);
            }
        }, null);
    }
}

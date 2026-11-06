package com.llmetor.dataagent.dataagent.safety;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全静态校验器（JSqlParser AST）。validateSql 工具与 executeSql 工具共享，形成双保险。
 *
 * <p>覆盖：白名单（仅 SELECT/WITH）、防多语句、危险函数黑名单（AST 全树递归 + 文本预扫）、
 * 禁 FOR UPDATE / SELECT INTO、OFFSET 必须带 ORDER BY、JOIN ≤ maxJoins、
 * 禁 CROSS JOIN / 无 ON JOIN（防笛卡尔积）、LIMIT 治理（缺失注入、超限下调）。
 * 解析失败一律拒绝（fail-closed）。
 */
@Slf4j
@Service
public class SqlSafetyGuard {

    /** 危险模式正则（文本预扫，防 AST 解析盲区） */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?i)\\bLOAD_FILE\\s*\\("                       // 读文件
                    + "|\\bINTO\\s+(OUTFILE|DUMPFILE)\\b"   // SELECT 写文件
                    + "|\\bSYSTEM_USER\\s*\\("              // 信息泄漏
                    + "|\\bFILE_READ|\\bFILE_WRITE"
    );

    /** AST 遍历要拦截的函数名（小写） */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "sleep", "benchmark", "get_lock", "release_lock",
            "is_free_lock", "is_used_lock", "master_pos_wait", "source_pos_wait",
            "load_file", "sys_exec", "sys_eval", "sys_get"
    );

    private final long maxRows;
    private final int maxJoins;

    public SqlSafetyGuard(@Value("${data-agent.safety.max-rows:200}") long maxRows,
                          @Value("${data-agent.safety.max-joins:3}") int maxJoins) {
        this.maxRows = maxRows;
        this.maxJoins = maxJoins;
    }

    /**
     * 校验 SQL。
     *
     * @return valid=true 时 safeSql 可直接执行；valid=false 时 reason 为修改建议
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.reject("SQL 为空");
        }
        String trimmed = sql.trim();
        // 去掉末尾分号，避免 parseStatements 误判
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        try {
            return doValidate(trimmed);
        } catch (net.sf.jsqlparser.JSQLParserException pe) {
            String msg = pe.getMessage() == null ? "" : pe.getMessage().replace("\n", " ");
            return ValidationResult.reject("SQL 语法解析失败：" + truncate(msg, 200));
        } catch (Exception e) {
            log.warn("SqlSafetyGuard 意外异常，保守拒绝 sql={} err={}", truncate(trimmed, 120), e.toString());
            return ValidationResult.reject("SQL 校验异常：" + e.getClass().getSimpleName());
        }
    }

    private ValidationResult doValidate(String sql) throws net.sf.jsqlparser.JSQLParserException {
        // 1. 原始文本预扫
        Matcher m = DANGEROUS_PATTERN.matcher(sql);
        if (m.find()) {
            return ValidationResult.reject("检测到危险模式：" + m.group().trim());
        }

        // 2. 解析——拦截多语句
        Statements stmts = CCJSqlParserUtil.parseStatements(sql);
        if (stmts.size() != 1) {
            return ValidationResult.reject("仅允许单条语句，检测到 " + stmts.size() + " 条（禁止用 ; 串联）");
        }
        Statement stmt = stmts.get(0);

        // 3. 白名单：只允许 SELECT（WITH 解析为 Select 子类）
        if (!(stmt instanceof Select select)) {
            return ValidationResult.reject("仅允许 SELECT/WITH 查询，禁止 " + stmt.getClass().getSimpleName()
                    + "（INSERT/UPDATE/DELETE/DDL/CALL 等均被拦截）");
        }

        // 4. AST 全树递归找危险函数
        String bad = new DangerousFunctionFinder().scan(select);
        if (bad != null) {
            return ValidationResult.reject("检测到危险函数：" + bad + "()");
        }

        // 5. 形态递归校验（CTE/UNION 分支/括号子查询/FROM 子查询/WHERE 子查询全覆盖）
        ValidationResult shapeResult = validateSelectShape(select);
        if (!shapeResult.valid()) {
            return shapeResult;
        }

        // 6. 用户 LIMIT 超阈值则下调
        capOuterLimit(select, maxRows);

        // 7. 无 LIMIT 注入一个
        if (select.getLimit() == null) {
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(maxRows));
            select.setLimit(limit);
        }

        return ValidationResult.ok(select.toString());
    }

    /** 递归校验查询形态：WITH 项、UNION 分支、括号子查询都走同一套规则 */
    private ValidationResult validateSelectShape(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() != null) {
                    ValidationResult result = validateSelectShape(item.getSelect());
                    if (!result.valid()) {
                        return result;
                    }
                }
            }
        }
        if (select instanceof PlainSelect ps) {
            return validatePlainSelectShape(ps);
        }
        if (select instanceof SetOperationList sol) {
            for (Select branch : sol.getSelects()) {
                ValidationResult result = validateSelectShape(branch);
                if (!result.valid()) {
                    return result;
                }
            }
        } else if (select instanceof ParenthesedSelect ps && ps.getSelect() != null) {
            return validateSelectShape(ps.getSelect());
        }
        return ValidationResult.ok(null);
    }

    private ValidationResult validatePlainSelectShape(PlainSelect ps) {
        if (ps.getForMode() != null || ps.getForUpdateTable() != null) {
            return ValidationResult.reject("禁止 FOR UPDATE / FOR SHARE（只读连接）");
        }
        if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
            return ValidationResult.reject("禁止 SELECT ... INTO（写文件/写变量/写表）");
        }
        if (ps.getOffset() != null && (ps.getOrderByElements() == null || ps.getOrderByElements().isEmpty())) {
            return ValidationResult.reject("分页查询使用 OFFSET 时必须带稳定 ORDER BY，建议按主表主键或时间字段 + 主键排序");
        }

        ValidationResult fromResult = validateFromItemShape(ps.getFromItem());
        if (!fromResult.valid()) {
            return fromResult;
        }
        if (ps.getWhere() != null) {
            ValidationResult whereResult = validateSubSelectsInExpression(ps.getWhere());
            if (!whereResult.valid()) {
                return whereResult;
            }
        }
        if (ps.getJoins() == null || ps.getJoins().isEmpty()) {
            return ValidationResult.ok(null);
        }
        if (ps.getJoins().size() > maxJoins) {
            return ValidationResult.reject("当前 SQL 关联表过多，最多允许 " + maxJoins
                    + " 个 JOIN。请拆成多步查询：先查主表分页，再按主键查询详情；如果是统计问题，请改为 GROUP BY 聚合查询");
        }

        for (Join join : ps.getJoins()) {
            ValidationResult joinResult = checkJoinShape(join);
            if (!joinResult.valid()) {
                return joinResult;
            }
            ValidationResult rightResult = validateFromItemShape(join.getRightItem());
            if (!rightResult.valid()) {
                return rightResult;
            }
            ValidationResult onResult = validateJoinOnExpressions(join);
            if (!onResult.valid()) {
                return onResult;
            }
        }
        return ValidationResult.ok(null);
    }

    /** 校验 JOIN 的全部 ON 表达式内嵌子查询（getOnExpression() 空列表会抛异常，走集合遍历） */
    private ValidationResult validateJoinOnExpressions(Join join) {
        if (join.getOnExpressions() == null) {
            return ValidationResult.ok(null);
        }
        for (Expression onExp : join.getOnExpressions()) {
            if (onExp == null) {
                continue;
            }
            ValidationResult onResult = validateSubSelectsInExpression(onExp);
            if (!onResult.valid()) {
                return onResult;
            }
        }
        return ValidationResult.ok(null);
    }

    /** 单个 JOIN 的形态检查：禁 CROSS / 逗号连接 / 无 ON·USING 的非 NATURAL JOIN（防笛卡尔积） */
    private ValidationResult checkJoinShape(Join join) {
        if (join.isCross()) {
            return ValidationResult.reject("禁止 CROSS JOIN。请补充明确的 JOIN ON 条件，避免产生笛卡尔积");
        }
        if (join.isSimple()) {
            return ValidationResult.reject("禁止无条件 JOIN（逗号连接）。请使用明确的 JOIN ON/USING 条件，避免产生笛卡尔积");
        }
        if (!join.isNatural() && !hasJoinCondition(join)) {
            return ValidationResult.reject("禁止无 ON/USING 条件的 JOIN。请补充明确的关联条件，避免产生笛卡尔积");
        }
        return ValidationResult.ok(null);
    }

    private boolean hasJoinCondition(Join join) {
        if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
            return true;
        }
        return join.getUsingColumns() != null && !join.getUsingColumns().isEmpty();
    }

    private ValidationResult validateFromItemShape(FromItem item) {
        if (item instanceof ParenthesedSelect ps && ps.getSelect() != null) {
            return validateSelectShape(ps.getSelect());
        }
        if (item instanceof ParenthesedFromItem pf) {
            ValidationResult fromResult = validateFromItemShape(pf.getFromItem());
            if (!fromResult.valid()) {
                return fromResult;
            }
            if (pf.getJoins() != null) {
                if (pf.getJoins().size() > maxJoins) {
                    return ValidationResult.reject("当前 SQL 括号 JOIN 关联表过多，最多允许 " + maxJoins
                            + " 个 JOIN。请拆成多步查询或改为聚合查询");
                }
                for (Join join : pf.getJoins()) {
                    ValidationResult joinResult = checkJoinShape(join);
                    if (!joinResult.valid()) {
                        return joinResult;
                    }
                    ValidationResult rightResult = validateFromItemShape(join.getRightItem());
                    if (!rightResult.valid()) {
                        return rightResult;
                    }
                }
            }
        }
        return ValidationResult.ok(null);
    }

    private ValidationResult validateSubSelectsInExpression(Expression expression) {
        SubSelectShapeValidator validator = new SubSelectShapeValidator();
        expression.accept(validator);
        return validator.result;
    }

    /** 用户 LIMIT 超阈值则下调到 maxRows（仅处理字面量 ROWCOUNT） */
    private void capOuterLimit(Select select, long maxRows) {
        Limit limit = select.getLimit();
        if (limit != null && limit.getRowCount() instanceof LongValue lv) {
            long userVal = lv.getValue();
            if (userVal > maxRows) {
                lv.setValue(maxRows);
                log.debug("用户 LIMIT {} > 阈值 {}，已下调", userVal, maxRows);
            }
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    /** WHERE/ON 表达式内嵌子查询的形态校验 */
    private class SubSelectShapeValidator extends ExpressionVisitorAdapter<Object> {
        private ValidationResult result = ValidationResult.ok(null);

        @Override
        public <S> Object visit(Select select, S context) {
            if (result.valid()) {
                result = validateSelectShape(select);
            }
            return super.visit(select, context);
        }
    }

    /**
     * 危险函数扫描器：复用 TablesNamesFinder 的全 AST 递归遍历能力，
     * 重写 visit(Function) 在所有表达式位置拦截危险函数。
     */
    private static class DangerousFunctionFinder extends TablesNamesFinder<Void> {
        private String found;

        /** 触发 AST 遍历并返回第一个命中的危险函数名（小写），无命中返回 null */
        String scan(Statement stmt) {
            found = null;
            getTableList(stmt); // TablesNamesFinder 内部会递归 visit 所有节点
            return found;
        }

        @Override
        public <S> Void visit(Function function, S context) {
            if (found == null && function.getName() != null) {
                String lower = function.getName().toLowerCase();
                if (DANGEROUS_FUNCTIONS.contains(lower)) {
                    found = lower;
                }
            }
            return super.visit(function, context); // 继续递归遍历
        }
    }
}

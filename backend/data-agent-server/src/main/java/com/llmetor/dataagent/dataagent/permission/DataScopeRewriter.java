package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据权限改写器（SQL 安全链第 ② 步，docs/01 §2.3）：把"谁能看多大范围"
 * 翻译成 WHERE/ON 条件注入 SQL AST。
 *
 * <p>递归 walk（PlainSelect → FROM 子项 / JOIN 右项 / WHERE·ON·HAVING 内嵌子查询；
 * UNION 逐分支；WITH 逐项；括号组向内）：
 * <ul>
 *   <li>FROM 主表 / INNER JOIN 右表 → 条件 AND 进 WHERE（INNER 语义下两者等价）</li>
 *   <li><b>LEFT JOIN 右表 → 条件 AND 进该 JOIN 的 ON</b>（进 WHERE 会把 NULL 扩展行
 *       过滤掉，LEFT 退化成 INNER）；USING 写法无法携带条件、无 ON 的 LEFT JOIN 直接拒绝</li>
 * </ul>
 *
 * <p>fail-closed 语义分层：结构上注入不进去（无 ON/USING 的 LEFT JOIN、部门树未就绪）
 * 抛 {@link RewriteException} 拒绝执行；改写过程的意外异常回落 SELF 重试（最小权限），
 * SELF 再失败才拒绝——权限出错时的正确姿势是"少给数据"。
 */
@Slf4j
@Service
public class DataScopeRewriter {

    private final List<PermissionRule> rules;
    private final DeptTreeCache deptTreeCache;
    private final DataScopeResolver resolver;
    private final boolean enabled;

    public DataScopeRewriter(List<PermissionRule> rules,
                             DeptTreeCache deptTreeCache,
                             DataScopeResolver resolver,
                             @Value("${data-agent.data-permission.enabled:false}") boolean enabled) {
        this.rules = rules;
        this.deptTreeCache = deptTreeCache;
        this.resolver = resolver;
        this.enabled = enabled;
    }

    /**
     * 按用户数据范围改写 SQL。
     *
     * @param safeSql guard 校验通过的安全 SQL（已注入 LIMIT）
     * @param userId  当前用户 ID（toolParams 白名单注入的真实值）
     * @return 改写结果（SQL + 生效范围 + 注入了条件的表，供审计日志）
     * @throws RewriteException 权限条件无法安全注入，应拒绝执行
     */
    public RewriteResult rewrite(String safeSql, Long userId) {
        if (!enabled) {
            return new RewriteResult(safeSql, DataScope.ALL, List.of());
        }
        DataScopeContext ctx = resolver.resolve(userId);
        if (ctx.injectNothing()) {
            return new RewriteResult(safeSql, DataScope.ALL, List.of());
        }
        // 意外异常回落 SELF 重试（最小权限）；RewriteException（含部门树未就绪）直接向上拒绝
        try {
            return doRewrite(safeSql, ctx);
        } catch (RewriteException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[data-permission] 改写异常，回落 SELF 重试: userId={}, err={}", userId, e.toString());
            return doRewrite(safeSql, DataScopeContext.self(userId));
        }
    }

    private RewriteResult doRewrite(String safeSql, DataScopeContext ctx) {
        Select select;
        try {
            select = (Select) CCJSqlParserUtil.parse(safeSql);
        } catch (Exception e) {
            // guard 已验证可解析，走到这里说明改写器内部缺陷——按 SELF 语义拒绝而非放行
            throw new RewriteException("数据权限改写阶段 SQL 解析失败：" + e.getMessage());
        }

        RewriteScope scope = buildScope(ctx);
        rewriteSelect(select, scope);

        String rewritten = select.toString();
        log.info("[data-permission] 改写完成 | scope={} 注入表={} | sql={}",
                ctx.dataScope(), scope.injectedTables, rewritten);
        return new RewriteResult(rewritten, ctx.dataScope(), List.copyOf(scope.injectedTables));
    }

    /** 解析 DEPT 系需要注入的部门集合：DEPT=直接挂载；DEPT_AND_SUB=树缓存 BFS 展开子孙 */
    private RewriteScope buildScope(DataScopeContext ctx) {
        Set<Long> effectiveDepts;
        if (ctx.dataScope() == DataScope.DEPT_AND_SUB) {
            effectiveDepts = deptTreeCache.expandWithDescendants(ctx.deptIds());
        } else {
            effectiveDepts = ctx.deptIds();
        }
        return new RewriteScope(ctx, effectiveDepts);
    }

    // ── AST 递归 walk ──

    private void rewriteSelect(Select select, RewriteScope scope) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() != null) {
                    rewriteSelect(item.getSelect(), scope);
                }
            }
        }
        if (select instanceof PlainSelect ps) {
            rewritePlainSelect(ps, scope);
        } else if (select instanceof SetOperationList sol) {
            for (Select branch : sol.getSelects()) {
                rewriteSelect(branch, scope);
            }
        } else if (select instanceof ParenthesedSelect p && p.getSelect() != null) {
            rewriteSelect(p.getSelect(), scope);
        }
    }

    private void rewritePlainSelect(PlainSelect ps, RewriteScope scope) {
        rewriteFromItem(ps, scope);
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                rewriteJoin(ps, join, scope);
            }
        }
        if (ps.getWhere() != null) {
            rewriteSubSelectsIn(ps.getWhere(), scope);
        }
        if (ps.getHaving() != null) {
            rewriteSubSelectsIn(ps.getHaving(), scope);
        }
    }

    private void rewriteFromItem(PlainSelect ps, RewriteScope scope) {
        FromItem item = ps.getFromItem();
        if (item instanceof Table t) {
            PermissionRule rule = matchRule(t.getName());
            if (rule != null) {
                Expression cond = buildCondition(rule, refOf(t), stripSchema(t.getName()).toLowerCase(), scope);
                andIntoWhere(ps, cond);
                scope.injectedTables.add(t.getName().toLowerCase());
            }
        } else if (item instanceof ParenthesedSelect p && p.getSelect() != null) {
            rewriteSelect(p.getSelect(), scope);
        }
    }

    private void rewriteJoin(PlainSelect ps, Join join, RewriteScope scope) {
        FromItem right = join.getRightItem();
        if (right instanceof Table t) {
            PermissionRule rule = matchRule(t.getName());
            if (rule != null) {
                Expression cond = buildCondition(rule, refOf(t), stripSchema(t.getName()).toLowerCase(), scope);
                if (join.isNatural()) {
                    throw new RewriteException(
                            "NATURAL JOIN 无法注入数据权限条件，请改写为明确的 JOIN ON 写法");
                }
                if (join.isLeft()) {
                    // LEFT JOIN：条件必须进 ON——进 WHERE 会过滤掉 NULL 扩展行，LEFT 退化为 INNER
                    if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                        Collection<Expression> onExps = join.getOnExpressions();
                        List<Expression> original = new ArrayList<>(onExps);
                        onExps.clear();
                        // AND 可交换：组合条件放回集合即可，顺序无关语义
                        onExps.add(new AndExpression(original.get(0), cond));
                    } else if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                        throw new RewriteException(
                                "LEFT JOIN 的 USING 写法无法注入数据权限条件，请改用 JOIN ON 写法");
                    } else {
                        throw new RewriteException(
                                "LEFT JOIN 无 ON 条件，数据权限过滤注入不进去，请补充 ON 后重试");
                    }
                } else {
                    andIntoWhere(ps, cond);
                }
                scope.injectedTables.add(t.getName().toLowerCase());
            }
        } else if (right instanceof ParenthesedSelect p && p.getSelect() != null) {
            rewriteSelect(p.getSelect(), scope);
        }
        if (join.getOnExpressions() != null) {
            for (Expression onExp : join.getOnExpressions()) {
                if (onExp != null) {
                    rewriteSubSelectsIn(onExp, scope);
                }
            }
        }
    }

    /** WHERE/ON/HAVING 表达式里的内嵌子查询递归改写（原地修改，同一对象引用无需替换） */
    private void rewriteSubSelectsIn(Expression expression, RewriteScope scope) {
        expression.accept(new SubSelectRewriter(scope));
    }

    private class SubSelectRewriter extends ExpressionVisitorAdapter<Object> {
        private final RewriteScope scope;

        SubSelectRewriter(RewriteScope scope) {
            this.scope = scope;
        }

        @Override
        public <S> Object visit(Select select, S context) {
            rewriteSelect(select, scope);
            return super.visit(select, context);
        }
    }

    // ── 条件生成与挂载 ──

    private PermissionRule matchRule(String tableName) {
        if (tableName == null) {
            return null;
        }
        String lower = stripSchema(tableName).toLowerCase();
        for (PermissionRule rule : rules) {
            if (rule.managedTables().contains(lower)) {
                return rule;
            }
        }
        return null;
    }

    private Expression buildCondition(PermissionRule rule, String ref, String tableName, RewriteScope scope) {
        if (scope.ctx.dataScope() == DataScope.SELF) {
            return rule.buildSelfCondition(ref, tableName, scope.ctx.userId());
        }
        return rule.buildDeptCondition(ref, tableName, scope.effectiveDepts);
    }

    private void andIntoWhere(PlainSelect ps, Expression cond) {
        ps.setWhere(ps.getWhere() == null ? cond : new AndExpression(ps.getWhere(), cond));
    }

    /** 表在当前 SELECT 作用域的引用名：优先别名，无别名用（剥掉库名的）表名 */
    private String refOf(Table table) {
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            return table.getAlias().getName();
        }
        return stripSchema(table.getName());
    }

    private static String stripSchema(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** 一次改写的内部作用域：上下文 + 展开后的部门集合 + 已注入表（审计用） */
    private static class RewriteScope {
        final DataScopeContext ctx;
        final Set<Long> effectiveDepts;
        final Set<String> injectedTables = new LinkedHashSet<>();

        RewriteScope(DataScopeContext ctx, Set<Long> effectiveDepts) {
            this.ctx = ctx;
            this.effectiveDepts = effectiveDepts;
        }
    }
}

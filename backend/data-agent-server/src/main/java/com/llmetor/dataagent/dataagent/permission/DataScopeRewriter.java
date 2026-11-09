package com.llmetor.dataagent.dataagent.permission;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
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
 *   <li>FROM 主表 / INNER JOIN 右表 → 条件 AND 进 WHERE（INNER 语义下两者等价）；
 *       原有 WHERE/ON 条件一律先包 Parenthesis 再 AND——防原条件顶层 OR 时
 *       权限条件被 AND 优先级缩进单分支造成绕过</li>
 *   <li><b>LEFT JOIN 右表 → 条件 AND 进该 JOIN 的 ON</b>（进 WHERE 会把 NULL 扩展行
 *       过滤掉，LEFT 退化成 INNER）；USING 写法无法携带条件、无 ON 的 LEFT JOIN 直接拒绝</li>
 * </ul>
 *
 * <p>条件由 {@link PermissionRule#buildCondition} 按可见范围并集生成，返回 null
 * （如 ALL 档的普通表）则该表跳过注入。ALL 档不再整体短路——doc_project 的
 * 草稿可见性等业务规则对所有档位生效。
 *
 * <p>fail-closed 语义分层：结构上注入不进去（无 ON/USING 的 LEFT JOIN）抛
 * {@link RewriteException} 拒绝执行；改写过程的意外异常回落 SELF 重试（最小权限），
 * SELF 再失败才拒绝——权限出错时的正确姿势是"少给数据"。
 */
@Slf4j
@Service
public class DataScopeRewriter {

    private final List<PermissionRule> rules;
    private final DataScopeResolver resolver;
    private final boolean enabled;

    public DataScopeRewriter(List<PermissionRule> rules,
                             DataScopeResolver resolver,
                             @Value("${data-agent.data-permission.enabled:false}") boolean enabled) {
        this.rules = rules;
        this.resolver = resolver;
        this.enabled = enabled;
    }

    /**
     * 按用户数据范围改写 SQL。
     *
     * @param safeSql guard 校验通过的安全 SQL（已注入 LIMIT）
     * @param userId  当前用户 ID（toolParams 白名单注入的真实值）
     * @return 改写结果（SQL + 生效范围摘要 + 注入了条件的表，供审计日志）
     * @throws RewriteException 权限条件无法安全注入，应拒绝执行
     */
    public RewriteResult rewrite(String safeSql, Long userId) {
        if (!enabled) {
            return new RewriteResult(safeSql, "OFF", List.of());
        }
        DataScopeContext ctx = resolver.resolve(userId);
        // 意外异常回落 SELF 重试（最小权限）；RewriteException（含部门树未就绪）直接向上拒绝
        try {
            return doRewrite(safeSql, ctx);
        } catch (RewriteException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[data-permission] 改写异常，回落 SELF 重试: userId={}, err={}", userId, e.toString());
            return doRewrite(safeSql, DataScopeContext.selfOnly(userId));
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

        RewriteState state = new RewriteState(ctx);
        rewriteSelect(select, state);

        String rewritten = select.toString();
        log.info("[data-permission] 改写完成 | scope={} 注入表={} | sql={}",
                ctx.summary(), state.injectedTables, rewritten);
        return new RewriteResult(rewritten, ctx.summary(), List.copyOf(state.injectedTables));
    }

    // ── AST 递归 walk ──

    private void rewriteSelect(Select select, RewriteState state) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() != null) {
                    rewriteSelect(item.getSelect(), state);
                }
            }
        }
        if (select instanceof PlainSelect ps) {
            rewritePlainSelect(ps, state);
        } else if (select instanceof SetOperationList sol) {
            for (Select branch : sol.getSelects()) {
                rewriteSelect(branch, state);
            }
        } else if (select instanceof ParenthesedSelect p && p.getSelect() != null) {
            rewriteSelect(p.getSelect(), state);
        }
    }

    private void rewritePlainSelect(PlainSelect ps, RewriteState state) {
        // ① 先递归改写用户书写的内嵌子查询（FROM 括号组 / JOIN 右项 / ON / WHERE / HAVING）——
        //    必须在注入之前：注入的权限条件自带子查询（成员项目/台账项目等），
        //    若注入后再走查会把自己注入的条件再改写一遍（草稿条件渗入子查询、
        //    成员子查询被二次过滤出假空集）。
        if (ps.getFromItem() instanceof ParenthesedSelect p && p.getSelect() != null) {
            rewriteSelect(p.getSelect(), state);
        }
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (join.getRightItem() instanceof ParenthesedSelect jp && jp.getSelect() != null) {
                    rewriteSelect(jp.getSelect(), state);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression onExp : join.getOnExpressions()) {
                        if (onExp != null) {
                            rewriteSubSelectsIn(onExp, state);
                        }
                    }
                }
            }
        }
        if (ps.getWhere() != null) {
            rewriteSubSelectsIn(ps.getWhere(), state);
        }
        if (ps.getHaving() != null) {
            rewriteSubSelectsIn(ps.getHaving(), state);
        }
        // ② 再注入本层 FROM 表 / JOIN 右表的权限条件（其携带的子查询不再被走查）
        if (ps.getFromItem() instanceof Table t) {
            injectIntoWhere(ps, t, state);
        }
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (join.getRightItem() instanceof Table t) {
                    injectForJoin(ps, join, t, state);
                }
            }
        }
    }

    private void injectIntoWhere(PlainSelect ps, Table t, RewriteState state) {
        Expression cond = conditionFor(t, state);
        if (cond != null) {
            andIntoWhere(ps, cond);
            state.injectedTables.add(t.getName().toLowerCase());
        }
    }

    private void injectForJoin(PlainSelect ps, Join join, Table t, RewriteState state) {
        Expression cond = conditionFor(t, state);
        if (cond == null) {
            return;
        }
        if (join.isNatural()) {
            throw new RewriteException(
                    "NATURAL JOIN 无法注入数据权限条件，请改写为明确的 JOIN ON 写法");
        }
        if (join.isLeft()) {
            // LEFT JOIN：条件必须进 ON——进 WHERE 会过滤掉 NULL 扩展行，LEFT 退化为 INNER
            Collection<Expression> onExps = join.getOnExpressions();
            if (onExps != null && !onExps.isEmpty()) {
                List<Expression> original = new ArrayList<>(onExps);
                onExps.clear();
                // AND 可交换：组合条件放回集合即可，顺序无关语义；
                // 原 ON 条件加括号（同 andIntoWhere：ON 内含 OR 时防权限条件被缩进单分支绕过）
                onExps.add(new AndExpression(parenthesize(original.get(0)), cond));
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
        state.injectedTables.add(t.getName().toLowerCase());
    }

    /** 命中管控表则生成条件；未纳管或无需注入（如 ALL 档普通表）返回 null */
    private Expression conditionFor(Table t, RewriteState state) {
        PermissionRule rule = matchRule(t.getName());
        if (rule == null) {
            return null;
        }
        return rule.buildCondition(refOf(t), stripSchema(t.getName()).toLowerCase(), state.ctx);
    }

    /** WHERE/ON/HAVING 表达式里的内嵌子查询递归改写（原地修改，同一对象引用无需替换） */
    private void rewriteSubSelectsIn(Expression expression, RewriteState state) {
        expression.accept(new SubSelectRewriter(state));
    }

    private class SubSelectRewriter extends ExpressionVisitorAdapter<Object> {
        private final RewriteState state;

        SubSelectRewriter(RewriteState state) {
            this.state = state;
        }

        @Override
        public <S> Object visit(Select select, S context) {
            rewriteSelect(select, state);
            return super.visit(select, context);
        }
    }

    // ── 条件挂载 ──

    /**
     * 表达式包 Parenthesis 节点（序列化必带括号）。经解析构造——当前 JSqlParser 版本的
     * Parenthesis 依赖预填内部列表，setExpression 会越界，只能从 "(expr)" 文本解析得到。
     */
    private static Expression parenthesize(Expression expression) {
        return ConditionBuilder.parse("(" + expression + ")");
    }

    private void andIntoWhere(PlainSelect ps, Expression cond) {
        if (ps.getWhere() == null) {
            ps.setWhere(cond);
            return;
        }
        // 原条件必须加括号再 AND：裸挂时序列化为 "原条件 AND 权限条件"，而 AND 优先级高于 OR，
        // 若原条件顶层是 OR（如 name LIKE '%a%' OR name LIKE '%b%'），权限条件会被缩进 OR 右分支，
        // 左分支完全绕过权限（2026-11 线上复现：OR 查询返回了无权限项目）。AST 层包 Parenthesis
        // 保证序列化恒为 "(原条件) AND (权限条件)"，语义由结构保证。
        ps.setWhere(new AndExpression(parenthesize(ps.getWhere()), cond));
    }

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

    /** 一次改写的内部状态：上下文 + 已注入表（审计用） */
    private static class RewriteState {
        final DataScopeContext ctx;
        final Set<String> injectedTables = new LinkedHashSet<>();

        RewriteState(DataScopeContext ctx) {
            this.ctx = ctx;
        }
    }
}

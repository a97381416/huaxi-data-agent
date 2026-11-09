package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataScopeRewriter 单元测试（M-H4 并集版回归）。
 * 引擎（AST 递归 walk / LEFT JOIN 进 ON / fail-closed）未动，
 * 用华西表语义覆盖：两段 OR 并集（部门∪本人）、兼职部门、草稿规则、
 * 台账子查询不渗草稿过滤、空并集恒假、ALL 档草稿规则仍生效。
 * resolver 用 Mockito 桩定，聚焦改写语义本身；resolver 解析逻辑见 DataScopeResolverTest。
 */
class DataScopeRewriterTest {

    private DataScopeResolver resolver;
    private DataScopeRewriter rewriter;

    @BeforeEach
    void setUp() {
        resolver = mock(DataScopeResolver.class);
        rewriter = new DataScopeRewriter(List.of(new YudaoDataScopeRule()), resolver, true);
    }

    private void stub(DataScopeContext ctx) {
        when(resolver.resolve(ctx.userId())).thenReturn(ctx);
    }

    // ── ALL：doc_project 仍注入草稿可见性（对齐主后端页面口径）；台账表不注入 ──

    @Test
    void allScopeStillInjectsDraftRuleOnProject() {
        stub(DataScopeContext.allOf(1L));
        RewriteResult r = rewriter.rewrite("SELECT id, name FROM doc_project", 1L);
        assertTrue(r.sql().contains("status <> 0"));
        assertTrue(r.sql().contains("creator = '1'"));
        assertFalse(r.sql().contains("dept_id IN"));
        assertEquals(List.of("doc_project"), r.injectedTables());
    }

    @Test
    void allScopeLeavesLedgerUnchanged() {
        stub(DataScopeContext.allOf(1L));
        String sql = "SELECT doc_number FROM doc_ledger";
        assertEquals(sql, rewriter.rewrite(sql, 1L).sql());
    }

    // ── SELF：仅 creator ──

    @Test
    void selfScopeTightenedToCreatorOnlyOnProject() {
        stub(DataScopeContext.selfOnly(7L));
        String sql = rewriter.rewrite("SELECT name FROM doc_project", 7L).sql();
        assertTrue(sql.contains("doc_project.creator = '7'"));
        assertFalse(sql.contains("doc_project_member"));
        assertTrue(sql.contains("status <> 0")); // 草稿规则与权限条件同时在场
    }

    @Test
    void selfScopeOnLedgerUsesCreatorInsideProjectSubquery() {
        stub(DataScopeContext.selfOnly(7L));
        String sql = rewriter.rewrite("SELECT doc_number FROM doc_ledger", 7L).sql();
        assertTrue(sql.contains(
                "doc_ledger.project_id IN (SELECT id FROM doc_project WHERE (doc_project.creator = '7')"));
    }

    // ── 6 档：兼职部门并入部门集合 ──

    @Test
    void concurrentDeptMergedIntoDeptCondition() {
        stub(new DataScopeContext(7L, false, false, Set.of(11L, 20L)));
        String sql = rewriter.rewrite("SELECT name FROM doc_project", 7L).sql();
        assertTrue(sql.contains("doc_project.dept_id IN ("));
        assertTrue(sql.contains("11"));
        assertTrue(sql.contains("20"));
        assertTrue(sql.contains("status <> 0"));
    }

    // ── 并集：部门 ∪ 本人 两段 OR 同时在场 ──

    @Test
    void unionCombinesDeptAndSelfSegments() {
        stub(new DataScopeContext(7L, false, true, Set.of(11L)));
        String sql = rewriter.rewrite("SELECT name FROM doc_project", 7L).sql();
        assertTrue(sql.contains("doc_project.dept_id IN (11)"));
        assertTrue(sql.contains("doc_project.creator = '7'"));
        assertFalse(sql.contains("doc_project_member")); // 7 档成员项目已废弃
    }

    // ── 台账表：子查询携带权限组合，但不含草稿过滤（对齐主后端台账口径）──

    @Test
    void ledgerSubqueryHasPermButNoDraftFilter() {
        stub(new DataScopeContext(7L, false, true, Set.of(11L)));
        String sql = rewriter.rewrite("SELECT doc_number FROM doc_ledger WHERE status = 2", 7L).sql();
        assertTrue(sql.contains("doc_ledger.project_id IN (SELECT id FROM doc_project WHERE"));
        assertTrue(sql.contains("doc_project.dept_id IN (11)"));
        assertFalse(sql.contains("status <> 0"));
    }

    // ── 空并集：fail-closed 恒假（对齐主后端 WHERE null = null）──

    @Test
    void emptyUnionFailsClosed() {
        stub(new DataScopeContext(7L, false, false, Set.of()));
        String sql = rewriter.rewrite("SELECT name FROM doc_project", 7L).sql();
        assertTrue(sql.contains("1 = 0"));
    }

    // ── 未纳管表：不注入 ──

    @Test
    void unmanagedTableNotInjected() {
        stub(DataScopeContext.selfOnly(7L));
        String sql = "SELECT label FROM system_dict_data WHERE dict_type = 'x'";
        assertEquals(sql, rewriter.rewrite(sql, 7L).sql());
    }

    // ── LEFT JOIN：条件进 ON 不进 WHERE（不退化 INNER）──

    @Test
    void leftJoinConditionGoesIntoOnClause() {
        stub(new DataScopeContext(7L, false, false, Set.of(11L)));
        RewriteResult r = rewriter.rewrite(
                "SELECT p.name FROM doc_project p LEFT JOIN doc_ledger l ON l.project_id = p.id", 7L);
        assertTrue(r.sql().toUpperCase().contains("LEFT JOIN"));
        // 原 ON 条件带括号（parenthesize 挂载，防 ON 内 OR 绕过）
        assertTrue(r.sql().contains("ON (l.project_id = p.id)"));
        // ON 上追加部门条件（不破坏 LEFT 语义），子查询无草稿过滤
        assertTrue(r.sql().contains(
                "l.project_id IN (SELECT id FROM doc_project WHERE (doc_project.dept_id IN (11))"));
        assertFalse(r.sql().contains("l.status <> 0"));
        // 主表 p：权限 + 草稿规则进 WHERE
        assertTrue(r.sql().contains("p.status <> 0"));
    }

    // ── 预警：self 档按责任人（对齐主后端 user 列注册，不再附带 creator）──

    @Test
    void warningSelfUsesResponsibleUser() {
        stub(DataScopeContext.selfOnly(7L));
        String sql = rewriter.rewrite("SELECT warning_code FROM doc_warning", 7L).sql();
        assertTrue(sql.contains("responsible_user_id = 7"));
        assertFalse(sql.contains("creator"));
    }

    // ── OR 绕过回归：WHERE 顶层 OR 时权限条件必须括号隔离（2026-11 线上复现的绕过）──

    @Test
    void orTopLevelWhereCannotBypassInjectedCondition() throws Exception {
        stub(DataScopeContext.selfOnly(7L));
        RewriteResult r = rewriter.rewrite(
                "SELECT id, name FROM doc_project WHERE name ILIKE '%产业园%' OR name ILIKE '%高新区%'", 7L);
        // 字符串形态：原 OR 组带括号，权限条件在括号外 AND（原查询列不带表前缀）
        assertTrue(r.sql().contains(
                "(name ILIKE '%产业园%' OR name ILIKE '%高新区%')"));
        assertTrue(r.sql().contains("doc_project.creator = '7'"));
        // 语义断言：改写后 SQL 重新解析，WHERE 顶层必须是 AND——括号一旦丢失，顶层退化为裸 OR
        Select reparsed = (Select) CCJSqlParserUtil.parse(r.sql());
        PlainSelect ps = (PlainSelect) reparsed;
        assertTrue(ps.getWhere() instanceof AndExpression,
                "WHERE 顶层必须是 AND，否则权限条件被 AND 优先级缩进 OR 单分支（绕过）");
    }

    // ── LEFT JOIN ON 内含 OR 时同样括号隔离（doc_warning 权限段自身含 OR，双重场景）──

    @Test
    void leftJoinOnWithOrAlsoParenthesized() throws Exception {
        stub(new DataScopeContext(7L, false, false, Set.of(11L)));
        RewriteResult r = rewriter.rewrite(
                "SELECT p.name FROM doc_project p LEFT JOIN doc_warning w "
                        + "ON w.project_id = p.id OR w.dept_id = p.dept_id", 7L);
        Select reparsed = (Select) CCJSqlParserUtil.parse(r.sql());
        Join join = ((PlainSelect) reparsed).getJoins().get(0);
        Expression on = join.getOnExpressions().iterator().next();
        assertTrue(on instanceof AndExpression,
                "ON 顶层必须是 AND，否则权限条件被 AND 优先级缩进 OR 单分支（绕过）");
    }
}

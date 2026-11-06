package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataScopeRewriter 单元测试（M-H2 华西版精简回归）。
 * 引擎（AST 递归 walk / LEFT JOIN 进 ON / fail-closed）与原版一致未动，
 * 此处用华西表语义覆盖核心分支；完整注入矩阵随 M-H5 评测验收补充。
 * resolver 用 Mockito 桩定，聚焦改写语义本身。
 * 树骨架：10 总公司 → 11 工程一部 / 12 工程二部；11 → 111 项目组A
 */
class DataScopeRewriterTest {

    private DataScopeResolver resolver;
    private DataScopeRewriter rewriter;

    @BeforeEach
    void setUp() {
        DeptTreeCache cache = new DeptTreeCache(Mockito.mock(DataSource.class));
        Map<Long, List<Long>> adjacency = new HashMap<>();
        adjacency.put(10L, List.of(11L, 12L));
        adjacency.put(11L, List.of(111L));
        ReflectionTestUtils.setField(cache, "children", adjacency);
        ReflectionTestUtils.setField(cache, "ready", true);

        resolver = mock(DataScopeResolver.class);
        rewriter = new DataScopeRewriter(List.of(new YudaoDataScopeRule()), cache, resolver, true);
    }

    // ── ALL：不注入 ──

    @Test
    void allScopeReturnsSqlUnchanged() {
        stub(1L, DataScope.ALL, Set.of());
        String sql = "SELECT id, name FROM doc_project";
        RewriteResult r = rewriter.rewrite(sql, 1L);
        assertEquals(sql, r.sql());
        assertTrue(r.injectedTables().isEmpty());
    }

    // ── SELF：doc_project 走成员表 + creator ──

    @Test
    void selfScopeInjectsMemberSubqueryOnProject() {
        stub(7L, DataScope.SELF, Set.of());
        RewriteResult r = rewriter.rewrite("SELECT name FROM doc_project", 7L);
        assertTrue(r.sql().contains("doc_project.id IN (SELECT project_id FROM doc_project_member"));
        assertTrue(r.sql().contains("doc_project.creator = '7'"));
        assertFalse(r.sql().equals("SELECT name FROM doc_project"));
    }

    @Test
    void selfScopeInjectsProjectIdSubqueryOnLedgerTable() {
        stub(7L, DataScope.SELF, Set.of());
        RewriteResult r = rewriter.rewrite("SELECT doc_number FROM doc_ledger", 7L);
        assertTrue(r.sql().contains("doc_ledger.project_id IN (SELECT project_id FROM doc_project_member"));
    }

    // ── DEPT：doc_project 走 dept_id；台账走部门子查询 ──

    @Test
    void deptScopeInjectsDeptIdOnProject() {
        stub(7L, DataScope.DEPT, Set.of(11L));
        RewriteResult r = rewriter.rewrite("SELECT name FROM doc_project", 7L);
        assertTrue(r.sql().contains("doc_project.dept_id IN (11)"));
    }

    @Test
    void deptAndSubScopeExpandsTree() {
        stub(7L, DataScope.DEPT_AND_SUB, Set.of(11L));
        RewriteResult r = rewriter.rewrite("SELECT name FROM doc_project", 7L);
        // 11 展开含 111
        assertTrue(r.sql().contains("doc_project.dept_id IN ("));
        assertTrue(r.sql().contains("111"));
    }

    @Test
    void deptScopeOnLedgerTableInjectsProjectSubquery() {
        stub(7L, DataScope.DEPT, Set.of(11L));
        RewriteResult r = rewriter.rewrite("SELECT doc_number FROM doc_ledger WHERE status = 2", 7L);
        assertTrue(r.sql().contains(
                "doc_ledger.project_id IN (SELECT id FROM doc_project WHERE dept_id IN (11)"));
    }

    // ── 未纳管表：不注入 ──

    @Test
    void unmanagedTableNotInjected() {
        stub(7L, DataScope.SELF, Set.of());
        String sql = "SELECT label FROM system_dict_data WHERE dict_type = 'x'";
        RewriteResult r = rewriter.rewrite(sql, 7L);
        assertEquals(sql, r.sql());
    }

    // ── LEFT JOIN：条件进 ON 不进 WHERE（不退化 INNER）──

    @Test
    void leftJoinConditionGoesIntoOnClause() {
        stub(7L, DataScope.DEPT, Set.of(11L));
        RewriteResult r = rewriter.rewrite(
                "SELECT p.name FROM doc_project p LEFT JOIN doc_ledger l ON l.project_id = p.id", 7L);
        assertTrue(r.sql().toUpperCase().contains("LEFT JOIN"));
        assertTrue(r.sql().contains("ON l.project_id = p.id"));
        // ON 上追加部门条件（不破坏 LEFT 语义）
        assertTrue(r.sql().contains("l.project_id IN (SELECT id FROM doc_project WHERE dept_id IN (11)"));
    }

    private void stub(Long userId, DataScope scope, Set<Long> deptIds) {
        when(resolver.resolve(userId)).thenReturn(new DataScopeContext(userId, scope, deptIds));
    }
}

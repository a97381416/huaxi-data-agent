package com.llmetor.dataagent.dataagent.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataScopeResolver 单元测试（M-H4 对齐主后端 PermissionServiceImpl#getDeptDataPermission）。
 *
 * <p>核心回归点（对应线上问题"只查出本人创建的项目"）：
 * 角色保存时 data_scope 只冗余写 data_scope_codes 的<b>最小值</b>（如 [5,6] 存 5），
 * 解析必须以 codes 多值并集为准，否则兼职部门(6)被吞、只剩 SELF；
 * 兼职部门唯一来源为 system_user_concurrent_dept 镜像表（不读 concurrent_dept_ids 列）。
 * JdbcTemplate 按 SQL 内容桩定（system_users / system_role / system_user_concurrent_dept 三条查询互不重叠）。
 */
class DataScopeResolverTest {

    private static final Long USER = 7L;

    private JdbcTemplate jdbc;
    private DeptTreeCache deptTreeCache;
    private DataScopeResolver resolver;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        deptTreeCache = mock(DeptTreeCache.class);
        resolver = new DataScopeResolver(jdbc, deptTreeCache);
    }

    private void stubUser(Long deptId) {
        when(jdbc.query(contains("FROM system_users"),
                ArgumentMatchers.<RowMapper<Object[]>>any(), ArgumentMatchers.eq(USER)))
                .thenReturn(Collections.singletonList(new Object[]{deptId}));
    }

    /** 兼职部门镜像表桩（6 档唯一来源；未桩定时 Mockito 默认返回空列表） */
    private void stubConcurrentDepts(Long... deptIds) {
        when(jdbc.query(contains("FROM system_user_concurrent_dept"),
                ArgumentMatchers.<RowMapper<Long>>any(), ArgumentMatchers.eq(USER)))
                .thenReturn(java.util.Arrays.asList(deptIds));
    }

    private void stubRoles(Object[]... roleRows) {
        when(jdbc.query(contains("FROM system_role"),
                ArgumentMatchers.<RowMapper<Object[]>>any(), ArgumentMatchers.eq(USER)))
                .thenReturn(java.util.Arrays.asList(roleRows));
    }

    /** 角色行：data_scope 单值（冗余最小值）/ data_scope_codes JSON / data_scope_dept_ids JSON */
    private static Object[] role(Integer singleScope, String codesJson, String deptIdsJson) {
        return new Object[]{singleScope, codesJson, deptIdsJson};
    }

    // ── 问题 1 回归：codes=[5,6] 时单值 data_scope=5，必须并出兼职部门 ──

    @Test
    void codesUnionWinsOverRedundantSingleValue() {
        stubUser(11L);                                // 主部门 11
        stubConcurrentDepts(20L, 21L);                // 兼职部门 20/21（镜像表）
        stubRoles(role(5, "[5, 6]", null));           // 保存时 data_scope 冗余写最小值 5
        DataScopeContext ctx = resolver.resolve(USER);
        assertFalse(ctx.all());
        assertTrue(ctx.self());
        assertEquals(Set.of(20L, 21L), ctx.deptIds()); // 6 档并入兼职部门（不含主部门）
    }

    // ── 存量角色：codes 为空回退 data_scope 单值 ──

    @Test
    void legacyRoleFallsBackToSingleValue() {
        stubUser(11L);
        stubRoles(role(3, null, null));               // 老角色只有 data_scope=3 本部门
        DataScopeContext ctx = resolver.resolve(USER);
        assertEquals(Set.of(11L), ctx.deptIds());
        assertFalse(ctx.self());
    }

    // ── 2 档：指定部门 + 本部门 ──

    @Test
    void customScopeAddsSpecifiedAndOwnDept() {
        stubUser(11L);
        stubRoles(role(2, "[2]", "[30, 31]"));
        assertEquals(Set.of(11L, 30L, 31L), resolver.resolve(USER).deptIds());
    }

    // ── 4 档：本部门及以下（树展开，对齐后端 getChildDeptIdListFromCache）──

    @Test
    void deptAndChildExpandsTree() {
        stubUser(11L);
        stubRoles(role(4, "[4]", null));
        when(deptTreeCache.expandWithDescendants(Set.of(11L))).thenReturn(Set.of(11L, 111L, 1111L));
        assertEquals(Set.of(11L, 111L, 1111L), resolver.resolve(USER).deptIds());
    }

    // ── 7 档已废弃：存量配置仍在时仅跳过（无成员折算，等价于只剩其余档位）──

    @Test
    void legacyMemberScopeDroppedSilently() {
        stubUser(11L);
        stubRoles(role(null, "[5, 7]", null));
        DataScopeContext ctx = resolver.resolve(USER);
        assertTrue(ctx.self());
        assertTrue(ctx.deptIds().isEmpty());
        assertEquals("self", ctx.summary());
    }

    // ── 多角色并集：A 角色 3 档 + B 角色 5 档 → 部门 ∪ 本人（不是取最大单档）──

    @Test
    void multiRoleUnionNotMax() {
        stubUser(11L);
        stubRoles(role(3, "[3]", null), role(5, "[5]", null));
        DataScopeContext ctx = resolver.resolve(USER);
        assertEquals(Set.of(11L), ctx.deptIds());
        assertTrue(ctx.self());
    }

    // ── 1 档短路 ALL ──

    @Test
    void allScopeShortCircuits() {
        stubUser(11L);
        stubRoles(role(1, "[1]", null));
        assertTrue(resolver.resolve(USER).all());
    }

    // ── 兜底：用户不存在 / 无启用角色 → 仅本人 ──

    @Test
    void unknownUserFallsToSelf() {
        when(jdbc.query(contains("FROM system_users"),
                ArgumentMatchers.<RowMapper<Object[]>>any(), ArgumentMatchers.eq(USER)))
                .thenReturn(Collections.emptyList());
        DataScopeContext ctx = resolver.resolve(USER);
        assertTrue(ctx.self());
        assertTrue(ctx.deptIds().isEmpty());
    }

    @Test
    void noRolesFallsToSelf() {
        stubUser(11L);
        stubRoles(); // 空角色列表
        DataScopeContext ctx = resolver.resolve(USER);
        assertTrue(ctx.self());
        assertTrue(ctx.deptIds().isEmpty());
    }

    // ── JSON 解析健壮性 ──

    @Test
    void parseHelpersHandleNullAndDirtyData() {
        assertTrue(DataScopeResolver.parseIdSet(null).isEmpty());
        assertTrue(DataScopeResolver.parseIdSet("[]").isEmpty());
        assertEquals(Set.of(1L, 2L), DataScopeResolver.parseIdSet("[1, 2]"));
        // 字符串元素兼容（线上 concurrent_dept_ids 实存 '["2061..."]' 的历史踩坑点）
        assertEquals(Set.of(1L, 2L), DataScopeResolver.parseIdSet("[\"1\", \"2\"]"));
        assertEquals(Set.of(2), DataScopeResolver.parseIntSet("[2, x, 3.5]")); // 脏数据忽略并告警
    }
}

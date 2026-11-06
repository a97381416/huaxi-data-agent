package com.llmetor.dataagent.dataagent.permission;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeptTreeCache 单元测试（M-H2 华西版）：BFS 子孙展开逻辑回归。
 * 数据源为 system_dept（JdbcTemplate 直查），测试经 ReflectionTestUtils 注入邻接表。
 * 树骨架（语义对齐原 sakila 版测试）：
 * 100 集团 → 101 总部 / 201 华东 / 301 华南
 * 201 → 210 上海 / 220 杭州 / 230 南京；210 → 211 上海销售 / 212 上海售后；220 → 221 杭州销售…
 */
class DeptTreeCacheTest {

    private DeptTreeCache cache;

    @BeforeEach
    void setUp() {
        cache = new DeptTreeCache(Mockito.mock(DataSource.class));
        Map<Long, List<Long>> adjacency = new HashMap<>();
        put(adjacency, 100L, 101L, 201L, 301L);
        put(adjacency, 201L, 210L, 220L, 230L);
        put(adjacency, 210L, 211L, 212L);
        put(adjacency, 220L, 221L, 222L);
        put(adjacency, 230L, 231L, 232L);
        ReflectionTestUtils.setField(cache, "children", adjacency);
        ReflectionTestUtils.setField(cache, "ready", true);
    }

    private static void put(Map<Long, List<Long>> m, long parent, long... children) {
        m.put(parent, java.util.Arrays.stream(children).boxed().toList());
    }

    @Test
    void expandKeepsSelfAndChildren() {
        Set<Long> result = cache.expandWithDescendants(Set.of(211L));
        assertEquals(Set.of(211L), result, "叶子部门展开只有自己");
    }

    @Test
    void expandSubtreeOfEastChina() {
        // 挂 201 华东大区，DEPT_AND_SUB 应展开 201 + 3 分公司 + 6 部门
        Set<Long> result = cache.expandWithDescendants(Set.of(201L));
        assertEquals(Set.of(201L, 210L, 220L, 230L, 211L, 212L, 221L, 222L, 231L, 232L), result);
    }

    @Test
    void expandUnionOfMultipleDepts() {
        // 挂 211 + 221，并集去重
        Set<Long> result = cache.expandWithDescendants(Set.of(211L, 221L));
        assertEquals(Set.of(211L, 221L), result);
    }

    @Test
    void expandDeduplicatesOverlappingSubtrees() {
        // 100 集团与 201 华东重叠，展开并集应无重复
        Set<Long> result = cache.expandWithDescendants(new java.util.LinkedHashSet<>(Set.of(100L, 201L)));
        assertEquals(Set.of(100L, 101L, 201L, 301L, 210L, 220L, 230L,
                211L, 212L, 221L, 222L, 231L, 232L), result);
    }

    @Test
    void notReadyRejectsExpansion() {
        // 未 load（或库不可达 load 失败）→ ready=false，展开 fail-closed 拒绝
        DeptTreeCache broken = new DeptTreeCache(Mockito.mock(DataSource.class));
        // 构造后不调 load()，ready 默认 false
        assertFalse(broken.isReady());
        assertThrows(DeptTreeCacheNotReadyException.class,
                () -> broken.expandWithDescendants(Set.of(100L)));
    }

    @Test
    void mapYudaoScopeAndParseDeptIds() {
        // M-H2：yudao 档位映射与 CUSTOM 部门串解析
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.ALL,
                DataScopeResolver.mapYudaoScope("1"));
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.CUSTOM,
                DataScopeResolver.mapYudaoScope("2"));
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.DEPT,
                DataScopeResolver.mapYudaoScope("3"));
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.DEPT_AND_SUB,
                DataScopeResolver.mapYudaoScope("4"));
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.SELF,
                DataScopeResolver.mapYudaoScope("5"));
        assertEquals(com.llmetor.dataagent.common.enums.DataScope.SELF,
                DataScopeResolver.mapYudaoScope(null));
        assertEquals(Set.of(1L, 2L, 3L), DataScopeResolver.parseDeptIds("[1, 2, 3]"));
        assertTrue(DataScopeResolver.parseDeptIds(null).isEmpty());
        assertTrue(DataScopeResolver.parseDeptIds("[a,b]").isEmpty(), "脏数据静默忽略");
    }
}

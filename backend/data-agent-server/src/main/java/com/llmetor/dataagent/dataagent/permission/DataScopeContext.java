package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;

import java.util.Set;

/**
 * 数据权限改写的输入上下文：当前用户的生效数据范围与部门集合。
 *
 * <p>由 {@link DataScopeResolver} 按用户角色解析：多角色取最大范围，
 * 解析失败回落 SELF（最小权限）。DEPT/DEPT_AND_SUB 时携带用户挂载的部门
 * （多部门并集，LinkedHashSet 去重保序）；SELF 时部门集合为空。
 *
 * @param userId    当前用户 ID
 * @param dataScope 生效数据范围（ALL 不注入）
 * @param deptIds   用户直接挂载的部门 ID（DEPT 用）；DEPT_AND_SUB 由改写器经 DeptTreeCache 展开后再用
 */
public record DataScopeContext(Long userId, DataScope dataScope, Set<Long> deptIds) {

    public static DataScopeContext all(Long userId) {
        return new DataScopeContext(userId, DataScope.ALL, Set.of());
    }

    public static DataScopeContext self(Long userId) {
        return new DataScopeContext(userId, DataScope.SELF, Set.of());
    }

    public boolean injectNothing() {
        return dataScope == DataScope.ALL;
    }
}

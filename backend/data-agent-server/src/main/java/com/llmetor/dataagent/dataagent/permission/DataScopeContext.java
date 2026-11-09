package com.llmetor.dataagent.dataagent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据权限改写的输入上下文：当前用户各来源可见范围的<b>并集</b>落点模型。
 *
 * <p>与主后端 {@code PermissionServiceImpl#getDeptDataPermission} 的
 * {@code DeptDataPermissionRespDTO} 对齐（all/self/deptIds；projectIds 成员档已废弃）：
 * 角色可多选数据范围（data_scope_codes 如 [3,6]）、用户可挂多角色，
 * 各档位全部折算进本模型后取并集，不再是"单一最大档"。
 *
 * <ul>
 *   <li>{@code all}：全部数据（任一角色勾选 1-全部），规则层短路不注入权限条件</li>
 *   <li>{@code deptIds}：部门落点并集——本部门(3)/本部门及以下(4，已展开子孙)/
 *       指定部门(2，含本部门)/<b>兼职部门(6，system_user_concurrent_dept 镜像表)</b></li>
 *   <li>{@code self}：仅本人（5），按各表 creator/责任人列注入</li>
 * </ul>
 *
 * @param userId  当前用户 ID
 * @param all     是否全部数据
 * @param self    是否含"仅本人"档
 * @param deptIds 部门 ID 并集（本部门及以下已在解析阶段展开子孙）
 */
public record DataScopeContext(Long userId, boolean all, boolean self, Set<Long> deptIds) {

    public static DataScopeContext allOf(Long userId) {
        return new DataScopeContext(userId, true, false, Set.of());
    }

    public static DataScopeContext selfOnly(Long userId) {
        return new DataScopeContext(userId, false, true, Set.of());
    }

    /**
     * 审计摘要（日志 / RewriteResult 用）：如
     * {@code ALL}、{@code dept[11,111]∪self}、{@code none}。
     */
    public String summary() {
        if (all) {
            return "ALL";
        }
        List<String> parts = new ArrayList<>();
        if (deptIds != null && !deptIds.isEmpty()) {
            parts.add("dept" + deptIds);
        }
        if (self) {
            parts.add("self");
        }
        return parts.isEmpty() ? "none" : String.join("∪", parts);
    }
}

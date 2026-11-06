package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据范围解析器（M-H2 华西版）：userId → 生效 DataScope + 直接部门集合。
 *
 * <p>数据源为华西 yudao 三表（同库直查，替代原自有 sys_* 表）：
 * <ul>
 *   <li>主部门：system_users.dept_id</li>
 *   <li>角色范围：system_user_role → system_role.data_scope（yudao 五档：
 *       1=全部 2=自定义 3=本部门 4=本部门及以下 5=仅本人）</li>
 *   <li>CUSTOM 档指定部门：system_role.data_scope_dept_ids（"[1,2,3]" 字符串，解析为集合并入）</li>
 * </ul>
 *
 * <p>多角色合并：取范围最大的档（沿用 {@link DataScope#max}），CUSTOM 的指定部门
 * 在任一角色出现即并入 deptIds。用户不存在/无角色 → SELF（最小权限）。
 *
 * <p>注：华西 system_role 另有 data_scope_codes（多档并集，如 [4,6,7]）扩展字段，
 * 一期按单一 data_scope 处理，并集语义联调时对齐（TODO M-H4）。
 */
@Slf4j
@Service
public class DataScopeResolver {

    private final JdbcTemplate jdbc;

    public DataScopeResolver(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public DataScopeContext resolve(Long userId) {
        // 1. 用户主部门（用户不存在 → SELF 最小权限）
        Long deptId = queryDeptId(userId);
        if (deptId == null) {
            log.warn("[data-permission] 用户不存在（system_users 无记录），回落 SELF: userId={}", userId);
            return DataScopeContext.self(userId);
        }

        // 2. 角色数据范围（yudao data_scope: 1 全部/2 自定义/3 本部门/4 本部门及以下/5 仅本人）
        List<Object[]> roles = jdbc.query(
                "SELECT r.data_scope, r.data_scope_dept_ids FROM system_role r "
                        + "JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0 AND r.status = 0",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}, userId);

        DataScope merged = DataScope.SELF;
        Set<Long> deptIds = new HashSet<>();
        deptIds.add(deptId); // DEPT 档直接挂载主部门
        for (Object[] role : roles) {
            DataScope ds = mapYudaoScope((String) role[0]);
            merged = DataScope.max(merged, ds);
            if (ds == DataScope.CUSTOM) {
                deptIds.addAll(parseDeptIds((String) role[1]));
            }
        }
        if (merged == DataScope.ALL) {
            return DataScopeContext.all(userId);
        }
        return new DataScopeContext(userId, merged, deptIds);
    }

    private Long queryDeptId(Long userId) {
        List<Long> ids = jdbc.query(
                "SELECT dept_id FROM system_users WHERE id = ? AND deleted = 0 AND status = 0",
                (rs, i) -> rs.getLong(1), userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** yudao 数字档位 → 本仓枚举（未知值按 SELF 最小权限） */
    public static DataScope mapYudaoScope(String v) {
        if (v == null) return DataScope.SELF;
        return switch (v.trim()) {
            case "1" -> DataScope.ALL;
            case "2" -> DataScope.CUSTOM;
            case "3" -> DataScope.DEPT;
            case "4" -> DataScope.DEPT_AND_SUB;
            case "5" -> DataScope.SELF;
            default -> DataScope.SELF;
        };
    }

    /** data_scope_dept_ids："[1,2,3]" 字符串 → 集合（脏数据静默忽略） */
    static Set<Long> parseDeptIds(String raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) return ids;
        String body = raw.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        for (String part : body.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }
}

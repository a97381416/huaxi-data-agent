package com.llmetor.dataagent.dataagent.permission;

import com.llmetor.dataagent.common.enums.DataScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据范围解析器（M-H4 对齐版）：userId → 各来源可见范围的并集落点。
 *
 * <p>与主后端 {@code PermissionServiceImpl#getDeptDataPermission(userId)} 逐档对齐
 * （同库直查；后端是唯一真相源，语义漂移即 bug）：
 * <ul>
 *   <li>角色范围：<b>data_scope_codes 多值并集优先</b>（如 "[3,6]"），
 *       为空才回退存量 data_scope 单值——主后端保存角色时 data_scope 只冗余写
 *       codes 的最小值，单看它会丢档（典型：[5,6] 存成 5，兼职部门被吞）</li>
 *   <li>档位：1 全部 / 2 自定义部门(指定部门+本部门) / 3 本部门 / 4 本部门及以下(展开子孙) /
 *       5 仅本人 / <b>6 兼职部门——唯一来源 system_user_concurrent_dept 镜像表，
 *       不读 system_users.concurrent_dept_ids 列（该列实为字符串数组
 *       '["2061..."]'，数字解析静默失败曾致兼职权限丢失）</li>
 *   <li>7 项目成员档已废弃：不折算、不查 doc_project_member（该表已移出 schema 白名单）</li>
 *   <li>多角色、角色内多档全部取并集（不是取最大单档）</li>
 *   <li>用户不存在 / 无启用角色 → SELF 最小权限（对齐后端"无角色仅本人"）</li>
 * </ul>
 */
@Slf4j
@Service
public class DataScopeResolver {

    private final JdbcTemplate jdbc;
    private final DeptTreeCache deptTreeCache;

    /** 多构造器场景，显式指定 Spring 用本构造器注入（另一构造器供单测直接注入 JdbcTemplate） */
    @Autowired
    public DataScopeResolver(DataSource dataSource, DeptTreeCache deptTreeCache) {
        this(new JdbcTemplate(dataSource), deptTreeCache);
    }

    /** 单测用：直接注入 JdbcTemplate 与部门树缓存 */
    DataScopeResolver(JdbcTemplate jdbc, DeptTreeCache deptTreeCache) {
        this.jdbc = jdbc;
        this.deptTreeCache = deptTreeCache;
    }

    public DataScopeContext resolve(Long userId) {
        // 1. 用户主部门（不存在 → SELF 最小权限）
        List<Object[]> users = jdbc.query(
                "SELECT dept_id FROM system_users WHERE id = ? AND deleted = 0",
                (rs, i) -> new Object[]{rs.getObject(1)}, userId);
        if (users.isEmpty()) {
            log.warn("[data-permission] 用户不存在（system_users 无记录），回落 SELF: userId={}", userId);
            return DataScopeContext.selfOnly(userId);
        }
        Long deptId = users.get(0)[0] == null ? null : ((Number) users.get(0)[0]).longValue();

        // 2. 启用角色（data_scope 多值/单值 + 自定义部门数组一并取出）
        List<Object[]> roles = jdbc.query(
                "SELECT r.data_scope, r.data_scope_codes, r.data_scope_dept_ids FROM system_role r "
                        + "JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0 AND r.status = 0",
                (rs, i) -> new Object[]{rs.getObject(1), rs.getString(2), rs.getString(3)}, userId);
        if (roles.isEmpty()) {
            // 对齐后端：无启用角色 → 仅本人
            return DataScopeContext.selfOnly(userId);
        }

        // 3. 逐角色逐档折算进落点模型（并集）
        boolean self = false;
        Set<Long> deptIds = new LinkedHashSet<>();
        Set<Long> concurrentDeptIds = null; // 惰性解析：角色含 6 档才查镜像表
        for (Object[] role : roles) {
            // ① data_scope_codes 多值优先；② 为空回退 data_scope 单值（存量角色行为不变）
            Set<Integer> scopes = parseIntSet((String) role[1]);
            if (scopes.isEmpty() && role[0] != null) {
                scopes = Set.of(((Number) role[0]).intValue());
            }
            for (Integer scope : scopes) {
                if (scope == null) {
                    continue;
                }
                switch (scope) {
                    case 1: // ALL：直接记全量标记，其余档无需再算
                        return DataScopeContext.allOf(userId);
                    case 2: // 自定义部门 = 指定部门 + 本部门
                        deptIds.addAll(parseIdSet((String) role[2]));
                        addIfPresent(deptIds, deptId);
                        break;
                    case 3: // 本部门
                        addIfPresent(deptIds, deptId);
                        break;
                    case 4: // 本部门及以下（解析期展开子孙，对齐后端 getChildDeptIdListFromCache）
                        addIfPresent(deptIds, deptId);
                        if (deptId != null) {
                            deptIds.addAll(deptTreeCache.expandWithDescendants(Set.of(deptId)));
                        }
                        break;
                    case 5: // 仅本人
                        self = true;
                        break;
                    case 6: // 兼职部门：仅兼职部门本身，不含子部门与主部门（与主后端口径一致）
                        if (concurrentDeptIds == null) {
                            concurrentDeptIds = queryConcurrentDeptIds(userId);
                        }
                        deptIds.addAll(concurrentDeptIds);
                        break;
                    default:
                        // 含已废弃的 7=项目成员；存量配置仍在时仅跳过并留痕
                        log.warn("[data-permission] 未知/已废弃数据范围档位，跳过: userId={}, scope={}", userId, scope);
                }
            }
        }
        return new DataScopeContext(userId, false, self, deptIds);
    }

    /**
     * 逐角色数据范围明细（UserContextBuilder 提示词展示用）：
     * 如 {@code "申报人员（本人、兼职部门）"}——回答"我能看哪些项目"时的依据说明。
     */
    public List<String> describeRoleScopes(Long userId) {
        List<Object[]> roles = jdbc.query(
                "SELECT r.name, r.data_scope, r.data_scope_codes FROM system_role r "
                        + "JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0 AND r.status = 0",
                (rs, i) -> new Object[]{rs.getString(1), rs.getObject(2), rs.getString(3)}, userId);
        List<String> result = new ArrayList<>();
        for (Object[] role : roles) {
            Set<Integer> scopes = parseIntSet((String) role[2]);
            if (scopes.isEmpty() && role[1] != null) {
                scopes = Set.of(((Number) role[1]).intValue());
            }
            List<String> names = new ArrayList<>();
            for (Integer scope : scopes) {
                String name = scopeName(scope);
                if (name != null) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                result.add(role[0] + "（" + String.join("、", names) + "）");
            }
        }
        return result;
    }

    /** 档位编码 → 业务名（7=项目成员已废弃，返回 null 不展示） */
    private static String scopeName(Integer scope) {
        if (scope == null) {
            return null;
        }
        return switch (scope) {
            case 1 -> "全部数据";
            case 2 -> "指定部门+本部门";
            case 3 -> "本部门";
            case 4 -> "本部门及以下";
            case 5 -> "本人";
            case 6 -> "兼职部门";
            default -> null;
        };
    }

    /**
     * 兼职部门唯一来源：{@code system_user_concurrent_dept} 镜像表。
     * 不读 system_users.concurrent_dept_ids 列——该列为字符串数组格式（'["2061..."]'），
     * 数字解析曾静默失败导致兼职权限丢失（2026-11 线上问题）。
     */
    private Set<Long> queryConcurrentDeptIds(Long userId) {
        return new LinkedHashSet<>(jdbc.query(
                "SELECT dept_id FROM system_user_concurrent_dept WHERE user_id = ? AND deleted = 0",
                (rs, i) -> rs.getLong(1), userId));
    }

    /** 部门 ID → 名称（UserContextBuilder 提示词展示用；查不到的部门不入 Map） */
    public Map<Long, String> getDeptNames(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Long id : deptIds) {
            List<String> rows = jdbc.query(
                    "SELECT name FROM system_dept WHERE id = ? AND deleted = 0",
                    (rs, i) -> rs.getString(1), id);
            if (!rows.isEmpty()) {
                names.put(id, rows.get(0));
            }
        }
        return names;
    }

    private static void addIfPresent(Set<Long> deptIds, Long deptId) {
        if (deptId != null && deptId > 0) {
            deptIds.add(deptId);
        }
    }

    /** yudao 数字档位 → 上游版枚举（登录 VO 展示沿用；权限链已不消费） */
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

    /** JSON 数组字符串（"[1,2,3]" 或 '["1","2"]'）→ Long 集合（脏数据忽略并告警） */
    static Set<Long> parseIdSet(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        List<String> bad = null;
        for (String part : splitJsonArray(raw)) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                if (bad == null) {
                    bad = new ArrayList<>();
                }
                bad.add(part);
            }
        }
        if (bad != null) {
            log.warn("[data-permission] ID 数组存在无法解析的元素（已忽略，请检查字段格式）: {}", bad);
        }
        return ids;
    }

    /** JSON 数组字符串（"[3,6]"）→ Integer 集合（脏数据忽略并告警） */
    static Set<Integer> parseIntSet(String raw) {
        Set<Integer> ints = new LinkedHashSet<>();
        List<String> bad = null;
        for (String part : splitJsonArray(raw)) {
            try {
                ints.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                if (bad == null) {
                    bad = new ArrayList<>();
                }
                bad.add(part);
            }
        }
        if (bad != null) {
            log.warn("[data-permission] 档位数组存在无法解析的元素（已忽略，请检查字段格式）: {}", bad);
        }
        return ints;
    }

    /**
     * JSON 数组字符串拆分为元素列表：剥掉外层 []，逐元素剥引号（兼容字符串元素，
     * 如 system_users.concurrent_dept_ids 实存 '["2061631541997129729"]'——历史踩坑点）。
     */
    private static List<String> splitJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String body = raw.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : body.split(",")) {
            String token = part.trim();
            if (token.length() >= 2
                    && ((token.startsWith("\"") && token.endsWith("\""))
                        || (token.startsWith("'") && token.endsWith("'")))) {
                token = token.substring(1, token.length() - 1).trim();
            }
            if (!token.isEmpty()) {
                parts.add(token);
            }
        }
        return parts;
    }
}

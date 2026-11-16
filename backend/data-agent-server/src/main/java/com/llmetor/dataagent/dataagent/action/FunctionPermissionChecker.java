package com.llmetor.dataagent.dataagent.action;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能权限校验器（M-E0）：写工具执行前按当前用户角色查 {@code system_role_menu}
 * → {@code system_menu}（type=3 按钮权限），确认用户被授予所需 permission（如
 * {@code doc:project:file:fill}）。
 *
 * <p>与既有数据权限（executeSql 行级注入）互补，构成「数据权限 + 功能权限」双模型；
 * REST 侧主后端仍会再校验一次（永真权威），本侧是前排拦截 + 省一次无效转发。
 *
 * <p>缓存：userId → 权限集 30s 本地缓存（口径对齐 HuaxiAuthFilter token 缓存；
 * 主后端改权最长 30s 后生效，高危动作有主后端二次校验兜底）。
 *
 * <p>无权限话术对齐「数据披露红线」风格：不泄露权限机制细节（不提表名/角色名/SQL）。
 */
@Slf4j
@Component
public class FunctionPermissionChecker {

    /** 无权限统一话术（面向 LLM 转述用户；不解释权限从哪来、怎么配） */
    public static final String DENY_MESSAGE = "您当前没有执行该操作的权限。如需开通，请联系管理员配置后再试。";

    private static final String PERMISSION_SQL = """
            SELECT DISTINCT m.permission
            FROM system_menu m
            JOIN system_role_menu rm ON rm.menu_id = m.id AND rm.deleted = 0
            JOIN system_user_role ur ON ur.role_id = rm.role_id AND ur.deleted = 0
            JOIN system_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 0
            WHERE ur.user_id = ? AND m.deleted = 0 AND m.status = 0 AND m.type = 3
              AND m.permission IS NOT NULL AND m.permission <> ''
            """;

    private final JdbcTemplate jdbc;

    /** userId → (权限集, 过期时刻)；30s 本地缓存 */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> permissions, long expiresAtMillis) {
    }

    private final long cacheTtlMillis;

    @Autowired
    public FunctionPermissionChecker(DataSource dataSource,
                                     @Value("${data-agent.action.permission-cache-ttl-ms:30000}") long cacheTtlMillis) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** 测试用构造同上：可注入短 TTL 验证缓存过期行为（直接 new，不 spring 化） */

    /** 当前用户全部按钮级权限标识（user→role→role_menu→menu，type=3） */
    public Set<String> loadPermissions(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(userId);
        if (hit != null && now < hit.expiresAtMillis()) {
            return hit.permissions();
        }
        Set<String> permissions = new HashSet<>(jdbc.query(
                PERMISSION_SQL, (rs, i) -> rs.getString(1), userId));
        cache.put(userId, new CacheEntry(Set.copyOf(permissions), now + cacheTtlMillis));
        if (log.isDebugEnabled()) {
            log.debug("[action] 权限加载: userId={}, count={}", userId, permissions.size());
        }
        return Set.copyOf(permissions);
    }

    /** 是否拥有某权限点 */
    public boolean hasPermission(Long userId, String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        return loadPermissions(userId).contains(permission);
    }

    /**
     * 校验权限：通过返回 null；未通过返回拒绝话术（直接回灌给 LLM，防反复重试）。
     * 内部 warn 记录 userId/permission（审计线索），话术不含机制细节。
     */
    public String checkPermission(Long userId, String permission) {
        if (hasPermission(userId, permission)) {
            return null;
        }
        log.warn("[action] 功能权限拦截: userId={}, permission={}", userId, permission);
        return "❌ " + DENY_MESSAGE + "（所需操作权限未开通，无需重试，请如实告知用户。）";
    }

    /** 主动失效（预留：主后端权限变更通知等场景） */
    public void evict(Long userId) {
        cache.remove(userId);
    }
}

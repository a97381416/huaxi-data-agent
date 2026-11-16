package com.llmetor.dataagent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 华西鉴权过滤器（M-H2，替代 Sa-Token 拦截器）。
 *
 * <p>协议与华西 yudao 一致：请求头 {@code Authorization: Bearer <access_token>}，
 * token 存华西库 system_oauth2_access_token——**华西签发的 token 天然可被本校验**
 * （为二期从华西前端跳转 SSO 铺路，届时零额外开发）。
 *
 * <p>校验：access_token 存在且未删除且未过期 → userId（M-E0 起连同 tenantId、原始 token）
 * 放入 {@link HuaxiAuthContext}；否则 401。token→userId 映射本地缓存 30s（削高频查表；
 * 登出/改密最长 30s 失效延迟，与 token 有效期相比可忽略）。
 *
 * <p>M-E0 tenantId 解析口径：优先 system_users.tenant_id（用户权威租户），
 * 缺失/为 0 时退 token 表 tenant_id。实测该部署租户号即为 0（多租户未启用），
 * 因此 0 是合法值原样透传——不假设默认租户 1（假设错了会导致 tenant-id 头与
 * 用户租户不一致，被启用租户校验的主后端 403 拒绝）。
 *
 * <p>白名单（对齐原 SaTokenConfig 契约）：登录接口、根路径、静态资源、
 * M8/M9 评测端点（生产应在网关禁用）。
 */
@Slf4j
@Component
@Order(1)
public class HuaxiAuthFilter extends OncePerRequestFilter {

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "html", "js", "css", "map",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "woff", "woff2", "ttf", "eot",
            "json", "txt", "md");

    /** 白名单前缀/精确路径（对齐原 SaTokenConfig 的 notMatch 契约） */
    private static final Set<String> WHITELIST_EXACT = Set.of(
            "/auth/login", "/",
            "/agent/eval/judge", "/agent/eval/diagnose",
            "/bird/eval/question", "/bird/eval/baseline");

    /** token → (userId, tenantId, expiresTimeMillis)；30s 本地缓存 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Long userId, Long tenantId, long expiresAtMillis) {
    }

    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final JdbcTemplate jdbc;

    public HuaxiAuthFilter(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                CacheEntry entry = resolveEntry(token);
                if (entry != null) {
                    HuaxiAuthContext.set(entry.userId(), entry.tenantId(), token);
                }
            }
            if (!isWhitelisted(request) && HuaxiAuthContext.getCurrentUserIdOrNull() == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已过期\"}");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            HuaxiAuthContext.clear();
        }
    }

    /** Authorization: Bearer xxx（兼容裸 token 头） */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("Bearer ")) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private CacheEntry resolveEntry(String token) {
        CacheEntry hit = cache.get(token);
        long now = System.currentTimeMillis();
        if (hit != null && now < hit.expiresAtMillis() && now < hit.expiresAtMillis() + CACHE_TTL_MILLIS) {
            return hit;
        }
        // M-E0：连同 token 表 tenant_id、用户表 tenant_id 一起取（join 一次查完）
        List<Object[]> rows = jdbc.query(
                "SELECT t.user_id, t.expires_time, t.tenant_id, u.tenant_id "
                        + "FROM system_oauth2_access_token t "
                        + "LEFT JOIN system_users u ON u.id = t.user_id AND u.deleted = 0 "
                        + "WHERE t.access_token = ? AND t.deleted = 0",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getTimestamp(2),
                        rs.getLong(3), rs.getLong(4)}, token);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        long expiresAt = ((java.sql.Timestamp) row[1]).getTime();
        if (expiresAt <= now) {
            return null; // 已过期（缓存负结果 30s，防过期 token 高频查表）
        }
        Long userId = (Long) row[0];
        // 用户表租户优先（权威）；缺失/为 0 退 token 表。0 是合法租户值（本部署多租户未启用），原样透传
        long userTenant = (Long) row[3];
        long tokenTenant = (Long) row[2];
        Long tenantId = userTenant != 0 ? userTenant : tokenTenant;
        CacheEntry entry = new CacheEntry(userId, tenantId, expiresAt);
        cache.put(token, entry);
        return entry;
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 上下文路径（如网关剥离 /agent-api 后转发）不影响判断
        String path = uri;
        if (WHITELIST_EXACT.contains(path)) {
            return true;
        }
        int dot = path.lastIndexOf('.');
        if (dot != -1 && STATIC_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase())) {
            return true;
        }
        return false;
    }
}

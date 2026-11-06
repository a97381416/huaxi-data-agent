package com.llmetor.dataagent.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 华西认证服务（M-H2）：登录校验 + token 签发，协议复刻华西 yudao
 * （AdminAuthServiceImpl / OAuth2TokenServiceImpl）。
 *
 * <ul>
 *   <li>账号校验：system_users.username + BCrypt matches（yudao 同款加密），
 *       status=0 且未删除才放行</li>
 *   <li>token 签发：fastSimpleUUID（32 位无横线，同 yudao）写入
 *       system_oauth2_access_token（与华西后端同表同协议）</li>
 * </ul>
 *
 * <p>有效时长：yudao 由 system_oauth2_client 按 client_id 配置；agent 侧取可配置
 * 默认 30 天（{@code data-agent.auth.token-valid-days}，联调时对齐华西 client 配置）。
 */
@Slf4j
@Service
public class HuaxiAuthService {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value("${data-agent.auth.token-valid-days:30}")
    private int tokenValidDays;

    public HuaxiAuthService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 登录：校验华西账号密码，签发 access token。
     *
     * @return accessToken（32 位 UUID，无横线，与 yudao 同规）
     * @throws HuaxiAuthException 账号不存在/停用/密码错（统一文案不区分，防探测）
     */
    public String login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new HuaxiAuthException("账号或密码错误");
        }
        java.util.List<Object[]> rows = jdbc.query(
                "SELECT id, password, nickname FROM system_users "
                        + "WHERE username = ? AND deleted = 0 AND status = 0",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, username);
        if (rows.isEmpty() || !ENCODER.matches(password, (String) rows.get(0)[1])) {
            log.warn("[huaxi-auth] 登录失败: username={}", username);
            throw new HuaxiAuthException("账号或密码错误");
        }
        long userId = (Long) rows.get(0)[0];
        String nickname = (String) rows.get(0)[2];
        String accessToken = issueAccessToken(userId);
        log.info("[huaxi-auth] 登录成功: userId={}, nickname={}", userId, nickname);
        return accessToken;
    }

    /** 当前登录用户（HuaxiAuthFilter 已解析）；未登录 null */
    public Long getCurrentUserIdOrNull() {
        return com.llmetor.dataagent.auth.HuaxiAuthContext.getCurrentUserIdOrNull();
    }

    /** 用户信息（UserContextBuilder 消费）：昵称/角色/主部门/生效数据范围 */
    public record HuaxiUserInfo(Long id, String username, String nickname,
                                 java.util.List<String> roles, String deptName,
                                 String dataScope) {
    }

    /** 查用户上下文信息（华西三表直查；用户不存在返回 null） */
    public HuaxiUserInfo getUserInfo(Long userId) {
        java.util.List<Object[]> users = jdbc.query(
                "SELECT id, username, nickname, dept_id FROM system_users "
                        + "WHERE id = ? AND deleted = 0",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)}, userId);
        if (users.isEmpty()) {
            return null;
        }
        Object[] u = users.get(0);
        long deptId = (Long) u[3];
        java.util.List<String> roleNames = jdbc.query(
                "SELECT r.name FROM system_role r JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0",
                (rs, i) -> rs.getString(1), userId);
        java.util.List<String> deptNames = jdbc.query(
                "SELECT name FROM system_dept WHERE id = ? AND deleted = 0",
                (rs, i) -> rs.getString(1), deptId);
        // 生效数据范围（与 DataScopeResolver 同口径：多角色取最大档）
        com.llmetor.dataagent.common.enums.DataScope merged = jdbc.query(
                "SELECT r.data_scope FROM system_role r JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0 AND r.status = 0",
                (rs, i) -> com.llmetor.dataagent.dataagent.permission.DataScopeResolver.mapYudaoScope(rs.getString(1)), userId)
                .stream()
                .reduce(com.llmetor.dataagent.common.enums.DataScope.SELF,
                        com.llmetor.dataagent.common.enums.DataScope::max);
        return new HuaxiUserInfo((Long) u[0], (String) u[1], (String) u[2],
                roleNames, deptNames.isEmpty() ? "" : deptNames.get(0), merged.name());
    }

    /** 签发 token（复刻 yudao OAuth2TokenServiceImpl：32 位无横线 UUID + 表存储） */
    private String issueAccessToken(long userId) {
        String accessToken = java.util.UUID.randomUUID().toString().replace("-", "");
        String refreshToken = java.util.UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(tokenValidDays);
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(user_id, user_type, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater) "
                        + "VALUES (?, 2, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?)",
                userId, accessToken, refreshToken, Timestamp.valueOf(expires),
                String.valueOf(userId), String.valueOf(userId));
        // 可选：刷新最后登录信息（与 yudao 行为一致；失败不阻断登录）
        try {
            jdbc.update("UPDATE system_users SET login_date = ? WHERE id = ?",
                    Timestamp.valueOf(now), userId);
        } catch (Exception ignored) {
        }
        return accessToken;
    }
}

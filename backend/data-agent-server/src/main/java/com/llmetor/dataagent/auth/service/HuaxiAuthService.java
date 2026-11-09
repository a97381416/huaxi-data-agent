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
     * 登录：校验华西账号密码，签发 access token，返回完整登录用户信息。
     *
     * @return LoginUserVO（含 token/roles/depts/dataScope，对齐上游前端契约）
     * @throws HuaxiAuthException 账号不存在/停用/密码错（统一文案不区分，防探测）
     */
    public com.llmetor.dataagent.auth.dto.LoginUserVO login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new HuaxiAuthException("账号或密码错误");
        }
        java.util.List<Object[]> rows = jdbc.query(
                "SELECT id, password, nickname, dept_id FROM system_users "
                        + "WHERE username = ? AND deleted = 0 AND status = 0",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)}, username);
        if (rows.isEmpty() || !ENCODER.matches(password, (String) rows.get(0)[1])) {
            log.warn("[huaxi-auth] 登录失败: username={}", username);
            throw new HuaxiAuthException("账号或密码错误");
        }
        long userId = (Long) rows.get(0)[0];
        String nickname = (String) rows.get(0)[2];
        long deptId = (Long) rows.get(0)[3];
        String accessToken = issueAccessToken(userId, nickname, deptId);
        log.info("[huaxi-auth] 登录成功: userId={}, nickname={}", userId, nickname);
        return buildLoginUser(userId, accessToken);
    }

    /**
     * 构建登录用户完整信息（登录 + /auth/me 共用）。
     *
     * @param token 当前会话 token；/auth/me 场景传 null（前端已有本地存根）
     */
    public com.llmetor.dataagent.auth.dto.LoginUserVO buildLoginUser(long userId, String token) {
        java.util.List<Object[]> users = jdbc.query(
                "SELECT username, nickname, dept_id, status FROM system_users "
                        + "WHERE id = ? AND deleted = 0",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)}, userId);
        if (users.isEmpty()) {
            throw new HuaxiAuthException("用户不存在: userId=" + userId);
        }
        Object[] u = users.get(0);

        com.llmetor.dataagent.auth.dto.LoginUserVO vo = new com.llmetor.dataagent.auth.dto.LoginUserVO();
        vo.setId(userId);
        vo.setUsername((String) u[0]);
        vo.setNickname((String) u[1]);
        // yudao status：0=正常 1=停用 → 前端契约 ACTIVE/DISABLED
        vo.setStatus((Integer) u[3] == 0 ? "ACTIVE" : "DISABLED");
        vo.setToken(token);

        // 角色（含 code——前端 isAdmin 判定 roles[].code === 'admin'）
        vo.setRoles(jdbc.query(
                "SELECT r.id, r.code, r.name FROM system_role r "
                        + "JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0",
                (rs, i) -> {
                    com.llmetor.dataagent.auth.dto.RoleSimpleVO r = new com.llmetor.dataagent.auth.dto.RoleSimpleVO();
                    r.setId(rs.getLong(1));
                    r.setCode(rs.getString(2));
                    r.setName(rs.getString(3));
                    return r;
                }, userId));

        // 部门：主部门 + 兼职部门（去重），各自带完整路径
        java.util.LinkedHashMap<Long, com.llmetor.dataagent.auth.dto.DeptSimpleVO> depts = new java.util.LinkedHashMap<>();
        long primaryDeptId = (Long) u[2];
        collectDept(depts, primaryDeptId);
        jdbc.query(
                "SELECT d.id FROM system_user_concurrent_dept cd "
                        + "JOIN system_dept d ON d.id = cd.dept_id AND d.deleted = 0 "
                        + "WHERE cd.user_id = ? AND cd.deleted = 0",
                (rs, i) -> rs.getLong(1), userId)
                .forEach(deptId -> collectDept(depts, deptId));
        vo.setDepts(new java.util.ArrayList<>(depts.values()));

        vo.setDataScope(mergedDataScope(userId).name());
        return vo;
    }

    /** 收集一个部门（含根→自己的完整路径；部门不存在/环路/深度超限则跳过） */
    private void collectDept(java.util.LinkedHashMap<Long, com.llmetor.dataagent.auth.dto.DeptSimpleVO> depts,
                             long deptId) {
        if (deptId <= 0 || depts.containsKey(deptId)) {
            return;
        }
        java.util.List<String> path = new java.util.ArrayList<>();
        long cursor = deptId;
        for (int depth = 0; depth < 10; depth++) {
            java.util.List<Object[]> rows = jdbc.query(
                    "SELECT name, parent_id FROM system_dept WHERE id = ? AND deleted = 0",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, cursor);
            if (rows.isEmpty()) {
                return; // 部门链断裂：整个部门不入列表
            }
            path.add(0, (String) rows.get(0)[0]); // 自底向上收集，插到头部
            long parent = (Long) rows.get(0)[1];
            if (parent <= 0 || parent == cursor) {
                break; // 到根 / 环路保护
            }
            cursor = parent;
        }
        com.llmetor.dataagent.auth.dto.DeptSimpleVO d = new com.llmetor.dataagent.auth.dto.DeptSimpleVO();
        d.setId(deptId);
        d.setName(path.isEmpty() ? "" : path.get(path.size() - 1));
        d.setPath(path);
        depts.put(deptId, d);
    }

    /** 多角色合并生效数据范围（取最大档；与 DataScopeResolver 同口径，禁用角色不计入） */
    private com.llmetor.dataagent.common.enums.DataScope mergedDataScope(long userId) {
        return jdbc.query(
                "SELECT r.data_scope FROM system_role r JOIN system_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND ur.deleted = 0 AND r.deleted = 0 AND r.status = 0",
                (rs, i) -> com.llmetor.dataagent.dataagent.permission.DataScopeResolver.mapYudaoScope(rs.getString(1)), userId)
                .stream()
                .reduce(com.llmetor.dataagent.common.enums.DataScope.SELF,
                        com.llmetor.dataagent.common.enums.DataScope::max);
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
        return new HuaxiUserInfo((Long) u[0], (String) u[1], (String) u[2],
                roleNames, deptNames.isEmpty() ? "" : deptNames.get(0), mergedDataScope(userId).name());
    }

    /**
     * 签发 token（复刻 yudao OAuth2TokenServiceImpl：32 位无横线 UUID + 表存储）。
     * 华西库该表 id/user_info 均 NOT NULL 且无默认值：id 用雪花（同主项目 MP ASSIGN_ID），
     * user_info 复刻主项目格式 {"nickname":"..","deptId":".."}（Map&lt;String,String&gt; JSON）。
     */
    private String issueAccessToken(long userId, String nickname, long deptId) {
        String accessToken = java.util.UUID.randomUUID().toString().replace("-", "");
        String refreshToken = java.util.UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(tokenValidDays);
        String userInfo = "{\"nickname\":" + jsonStr(nickname) + ",\"deptId\":\"" + deptId + "\"}";
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(id, user_id, user_type, user_info, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater) "
                        + "VALUES (?, ?, 2, ?, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                userId, userInfo, accessToken, refreshToken, Timestamp.valueOf(expires),
                String.valueOf(userId), String.valueOf(userId));
        // 可选：刷新最后登录信息（与 yudao 行为一致；失败不阻断登录）
        try {
            jdbc.update("UPDATE system_users SET login_date = ? WHERE id = ?",
                    Timestamp.valueOf(now), userId);
        } catch (Exception ignored) {
        }
        return accessToken;
    }

    /** 最小 JSON 字符串转义（nickname 场景：反斜杠 + 引号足够） */
    private static String jsonStr(String s) {
        if (s == null) {
            s = "";
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}

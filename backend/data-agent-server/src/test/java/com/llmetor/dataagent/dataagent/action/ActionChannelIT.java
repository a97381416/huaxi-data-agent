package com.llmetor.dataagent.dataagent.action;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-E0 端到端验收（IT，默认不随单测跑，显式 -Dtest=ActionChannelIT 执行）：
 * 以真实环境验证「agent → 主后端 REST」写通道三件套。
 *
 * <p>环境（application-local.yml 同款）：
 * <ul>
 *   <li>agent 库：PG 192.168.2.61/zrkj-huaxi（pgvector）——system_* 与主后端同库同表</li>
 *   <li>主后端：http://192.168.2.77:9031/admin-api（yudao，验证码开启无法密码登录，
 *       走同表铸 token 通道——这正是 M-H2「同表同校验」的设计前提）</li>
 * </ul>
 *
 * <p>流程：选真实申报用户 → 向 system_oauth2_access_token 铸一枚短时效 token（2h，用后即删）
 * → RestActionClient 真实调 get-permission-info（Bearer + tenant-id 过 yudao 鉴权链）
 * → FunctionPermissionChecker 真实查 system_role_menu 比对两侧权限口径 → 清理。
 */
@Tag("integration")
class ActionChannelIT {

    private static final String DB_URL =
            "jdbc:postgresql://192.168.2.61:5432/zrkj-huaxi?currentSchema=public";
    private static final String DB_USER = "pgvector";
    private static final String DB_PASS = "pgvector";
    private static final String MAIN_BACKEND = "http://192.168.2.77:9031/admin-api";

    private JdbcTemplate jdbc;
    private String mintedToken;
    private Long testUserId;
    private Long testTenantId;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASS);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null && mintedToken != null) {
            int removed = jdbc.update(
                    "DELETE FROM system_oauth2_access_token WHERE access_token = ?", mintedToken);
            System.out.println("[IT] 清理铸 token: removed=" + removed);
        }
    }

    @Test
    void endToEndRestChannelAndPermissionCheck() {
        // ① 选一个真实申报用户（shenbao1，计划文档 1.1 的 trace 主角）
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, tenant_id FROM system_users WHERE username = 'shenbao1' AND deleted = 0 AND status = 0");
        assertThat(users).as("库中应存在启用的 shenbao1").isNotEmpty();
        testUserId = ((Number) users.get(0).get("id")).longValue();
        testTenantId = ((Number) users.get(0).get("tenant_id")).longValue();
        System.out.println("[IT] shenbao1: userId=" + testUserId + ", tenantId=" + testTenantId);

        // ② 同表铸 token（对齐 HuaxiAuthService.issueAccessToken 格式，2 小时时效）
        mintedToken = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(id, user_id, user_type, user_info, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater, tenant_id) "
                        + "VALUES (?, ?, 2, ?, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?, ?)",
                IdWorker.getId(), testUserId,
                "{\"nickname\":\"M-E0-验收\",\"deptId\":\"0\"}",
                mintedToken, UUID.randomUUID().toString().replace("-", ""),
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)),
                String.valueOf(testUserId), String.valueOf(testUserId), testTenantId);

        // ③ RestActionClient 真实转发主后端（Bearer + tenant-id 完整鉴权链）
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        RestActionClient.ActionResult result = client.get(
                "/system/auth/get-permission-info", Map.of(), mintedToken, testTenantId);

        System.out.println("[IT] REST 转发结果: success=" + result.success()
                + ", httpStatus=" + result.httpStatus()
                + ", errorType=" + result.errorType()
                + (result.msg() != null ? ", msg=" + result.msg() : ""));
        assertThat(result.success()).as("主后端应认可同表铸出的 token：" + result.msg()).isTrue();
        assertThat(result.data()).isNotNull();
        assertThat(result.data().has("permissions")).isTrue();

        int restPermCount = result.data().path("permissions").size();
        System.out.println("[IT] 主后端侧权限数: " + restPermCount);

        // ④ FunctionPermissionChecker 本地侧口径（system_role_menu 四表 JOIN）
        FunctionPermissionChecker checker = new FunctionPermissionChecker(
                jdbc.getDataSource(), 30_000);
        Set<String> localPerms = checker.loadPermissions(testUserId);
        System.out.println("[IT] agent 侧权限数: " + localPerms.size());
        System.out.println("[IT] agent 侧权限样例: "
                + localPerms.stream().sorted().limit(8).toList());

        // 两侧都是「当前用户被授予的按钮级权限」：非空且核心申报权限在列
        assertThat(localPerms).isNotEmpty();
        assertThat(localPerms).as("申报人员应有资料填报权限")
                .contains("doc:project:file:fill");
        assertThat(restPermCount).as("主后端侧权限清单不应为空").isGreaterThan(0);

        // ⑤ 无权限话术（拿一个肯定不存在的权限点）
        String deny = checker.checkPermission(testUserId, "doc:not:exist:perm");
        System.out.println("[IT] 无权限话术: " + deny);
        assertThat(deny).contains("没有执行该操作的权限");
    }

    @Test
    void observeWrongTenantBehavior() {
        // 观察用例（非硬断言）：故意发错 tenant-id，记录主后端租户校验行为。
        // 实测 192.168.2.77 部署未启用 yudao 多租户校验（tenant.enable=false 形态），
        // 错误 tenant-id 也会放行——租户防御由启用该功能的主后端环境负责；
        // 本用例留作环境差异观测点（启用租户的环境此处应返回 403）。token 表 tenant_id 实测全为 0。
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, tenant_id FROM system_users WHERE username = 'shenbao1' AND deleted = 0");
        testUserId = ((Number) users.get(0).get("id")).longValue();
        testTenantId = ((Number) users.get(0).get("tenant_id")).longValue();

        mintedToken = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(id, user_id, user_type, user_info, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater, tenant_id) "
                        + "VALUES (?, ?, 2, ?, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?, ?)",
                IdWorker.getId(), testUserId,
                "{\"nickname\":\"M-E0-验收\",\"deptId\":\"0\"}",
                mintedToken, UUID.randomUUID().toString().replace("-", ""),
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)),
                String.valueOf(testUserId), String.valueOf(testUserId), testTenantId);

        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        long wrongTenant = testTenantId == 1L ? 999L : 1L;
        RestActionClient.ActionResult result = client.get(
                "/system/auth/get-permission-info", Map.of(), mintedToken, wrongTenant);

        System.out.println("[IT][观测] 错误 tenant-id 响应: success=" + result.success()
                + ", errorType=" + result.errorType()
                + (result.msg() != null ? ", msg=" + result.msg() : "")
                + "（success=true 说明该部署未启用租户校验；启用时应为 403）");
    }
}

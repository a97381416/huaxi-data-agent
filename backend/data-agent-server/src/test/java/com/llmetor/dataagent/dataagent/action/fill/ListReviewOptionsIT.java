package com.llmetor.dataagent.dataagent.action.fill;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-E2 读路径集成冒烟（IT，默认不随单测跑，显式 -Dtest=ListReviewOptionsIT 执行）：
 * 真实环境跑 listReviewOptions，重点回归「项目名当 projectId 透传 → 主平台 400」缺陷。
 *
 * <p>用户实测报错：For input string: "南三高速公路项目"（主平台 /doc/recommend/assignees
 * 的 projectId 是 Long）。修复后工具应先把 名称/ID/编码 定位成数字 ID 再调主平台，
 * 本 IT 用真实项目名 + 真实子阶段名/数字 ID 三种入参各跑一遍。
 *
 * <p>环境与铸 token 通道同 TaskReadIT：PG 192.168.2.61 同表铸 token、
 * 主后端 192.168.2.77:9031、用后即删。审计打桩（不打 agentx_action_log，保持库零痕迹）。
 */
@Tag("integration")
class ListReviewOptionsIT {

    private static final String DB_URL =
            "jdbc:postgresql://192.168.2.61:5432/zrkj-huaxi?currentSchema=public";
    private static final String DB_USER = "pgvector";
    private static final String DB_PASS = "pgvector";
    private static final String MAIN_BACKEND = "http://192.168.2.77:9031/admin-api";
    /** 用户实测报错时的项目名（若 shenbao1 名下没有则自动回退其首个项目） */
    private static final String REPORTED_PROJECT = "南三高速公路项目";

    private JdbcTemplate jdbc;
    private String mintedToken;
    private Long testUserId;
    private Long testTenantId;
    private String testNickname;

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

    private void mintToken() {
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, tenant_id, nickname FROM system_users WHERE username = 'shenbao1' AND deleted = 0 AND status = 0");
        assertThat(users).as("库中应存在启用的 shenbao1").isNotEmpty();
        testUserId = ((Number) users.get(0).get("id")).longValue();
        testTenantId = ((Number) users.get(0).get("tenant_id")).longValue();
        testNickname = (String) users.get(0).get("nickname");

        mintedToken = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(id, user_id, user_type, user_info, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater, tenant_id) "
                        + "VALUES (?, ?, 2, ?, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?, ?)",
                IdWorker.getId(), testUserId,
                "{\"nickname\":\"ListReviewOptionsIT\",\"deptId\":\"0\"}",
                mintedToken, UUID.randomUUID().toString().replace("-", ""),
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)),
                String.valueOf(testUserId), String.valueOf(testUserId), testTenantId);
        System.out.println("[IT] shenbao1: userId=" + testUserId + ", tenantId=" + testTenantId);
    }

    private ListReviewOptionsTool newTool(RestActionClient client, FillContextSupport support) {
        ActionAuditLogger quietAudit = new ActionAuditLogger(jdbc.getDataSource()) {
            @Override
            public void log(Entry entry) {
                System.out.println("[IT][audit-muted] tool=" + entry.tool()
                        + " business=" + entry.businessObject());
            }
        };
        return new ListReviewOptionsTool(client, quietAudit, support);
    }

    /** shenbao1 名下的可用项目名（优先用户报错里的真实项目名） */
    private String pickProjectName(FillContextSupport support, String token, Long tenant) {
        try {
            FillContextSupport.ProjectRef p = support.findProject(REPORTED_PROJECT, token, tenant);
            assertThat(p.projectId()).as("定位到的 projectId 应为纯数字").matches("\\d+");
            System.out.println("[IT] 命中用户报错项目: " + p.projectName() + " -> " + p.projectId());
            return p.projectName();
        } catch (FillContextSupport.TaskFlowException e) {
            System.out.println("[IT] shenbao1 名下无「" + REPORTED_PROJECT + "」，回退首个项目: "
                    + e.getMessage().split("\n")[0]);
        }
        RestActionClient.ActionResult r = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1)
                .get("/doc/workbench/my-projects",
                        FillContextSupport.query("pageSize", "5"), token, tenant);
        assertThat(r.success()).as("查询我的项目应成功").isTrue();
        JsonNode first = r.data().path("list").path(0);
        assertThat(first.path("projectName").asText(""))
                .as("shenbao1 名下应有进行中的项目").isNotEmpty();
        return first.path("projectName").asText();
    }

    /** 项目首个子阶段 [id, name, code]（走 /doc/project/detail + phase-children，同 resolvePhase 数据源） */
    private String[] firstSubStage(RestActionClient client, FillContextSupport support,
                                   String projectName, String token, Long tenant) {
        FillContextSupport.ProjectRef project = support.findProject(projectName, token, tenant);
        RestActionClient.ActionResult detail = client.get("/doc/project/detail",
                FillContextSupport.query("projectId", project.projectId()), token, tenant);
        assertThat(detail.success()).as("查询项目阶段树应成功").isTrue();
        for (JsonNode major : detail.data().path("majorPhases")) {
            RestActionClient.ActionResult cr = client.get("/doc/project/phase-children",
                    FillContextSupport.query("projectId", project.projectId(),
                            "majorPhaseId", major.path("phaseId").asText()), token, tenant);
            if (!cr.success()) {
                continue;
            }
            for (JsonNode sub : cr.data().path("list")) {
                String id = sub.path("id").asText("");
                if (!id.isEmpty()) {
                    System.out.println("[IT] 取子阶段: " + major.path("phaseName").asText()
                            + " / " + sub.path("name").asText()
                            + "（id=" + id + ", code=" + sub.path("code").asText() + "）");
                    return new String[]{id, sub.path("name").asText(), sub.path("code").asText()};
                }
            }
        }
        throw new IllegalStateException("项目「" + projectName + "」下没有任何子阶段，请先在主平台配置");
    }

    /** 回归主缺陷：项目名 + 子阶段名（都是对话里 LLM 拿到的自然形态）不再 400 */
    @Test
    void byProjectNameAndPhaseNameOnRealData() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        FillContextSupport support = new FillContextSupport(client);
        ListReviewOptionsTool tool = newTool(client, support);
        String tenant = String.valueOf(testTenantId);

        String projectName = pickProjectName(support, mintedToken, testTenantId);
        String[] sub = firstSubStage(client, support, projectName, mintedToken, testTenantId);

        String out = tool.listReviewOptions(projectName, sub[1], null, null,
                String.valueOf(testUserId), mintedToken, tenant, "it-conv");
        System.out.println("[IT] 项目名+子阶段名 输出（截断 800 字）：\n"
                + out.substring(0, Math.min(800, out.length())));
        assertThat(out).as("应成功返回提审配置选项").contains("提审配置选项");
        assertThat(out).as("不应再出现参数类型转换报错")
                .doesNotContain("Failed to convert", "查询常用审核人失败");
    }

    /** 数字 ID 直传路径不受影响（回归保护） */
    @Test
    void byNumericIdsOnRealData() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        FillContextSupport support = new FillContextSupport(client);
        ListReviewOptionsTool tool = newTool(client, support);
        String tenant = String.valueOf(testTenantId);

        String projectName = pickProjectName(support, mintedToken, testTenantId);
        String[] sub = firstSubStage(client, support, projectName, mintedToken, testTenantId);

        String out = tool.listReviewOptions(projectName, sub[0], null, null,
                String.valueOf(testUserId), mintedToken, tenant, "it-conv");
        System.out.println("[IT] 数字ID 输出首行：\n"
                + out.substring(0, Math.min(300, out.length())));
        assertThat(out).contains("提审配置选项");
        assertThat(out).doesNotContain("Failed to convert", "查询常用审核人失败");
    }

    /** 姓名全局反查（userNameKeyword，②模式）：用 shenbao1 自己的昵称，应命中并给出 ID */
    @Test
    void reverseLookupByNicknameOnRealData() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        FillContextSupport support = new FillContextSupport(client);
        ListReviewOptionsTool tool = newTool(client, support);
        String tenant = String.valueOf(testTenantId);

        assertThat(testNickname).as("shenbao1 应有昵称").isNotBlank();
        String out = tool.listReviewOptions(null, null, testNickname, null,
                String.valueOf(testUserId), mintedToken, tenant, "it-conv");
        System.out.println("[IT] 姓名反查（" + testNickname + "）首行：\n" + out.split("\n")[0]);
        assertThat(out).as("反查应命中").containsAnyOf("唯一命中", "姓名含");
        assertThat(out).as("命中结果应含昵称与ID").contains(testNickname, "ID:");
    }

    /** 项目名错拼/不存在 → 人话候选清单，而不是主平台 400 原文 */
    @Test
    void unknownProjectRejectedGracefully() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        FillContextSupport support = new FillContextSupport(client);
        ListReviewOptionsTool tool = newTool(client, support);
        String tenant = String.valueOf(testTenantId);

        String out = tool.listReviewOptions("绝对不存在项目XYZQ", "任意阶段", null, null,
                String.valueOf(testUserId), mintedToken, tenant, "it-conv");
        System.out.println("[IT] 不存在项目 输出首行：\n" + out.split("\n")[0]);
        assertThat(out).contains("未找到");
        assertThat(out).doesNotContain("Failed to convert");
    }
}

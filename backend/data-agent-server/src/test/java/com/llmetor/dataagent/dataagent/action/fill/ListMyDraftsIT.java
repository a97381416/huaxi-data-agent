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
 * 草稿盘点读路径集成冒烟（IT，默认不随单测跑，显式 -Dtest=ListMyDraftsIT 执行）：
 * 真实环境跑 listMyDrafts（跨子阶段盘点 + 单子阶段详情展开）。
 *
 * <p>shenbao1 名下有真实草稿台账（doc_ledger status=0）；环境与铸 token 通道同
 * TaskReadIT/ListReviewOptionsIT：PG 192.168.2.61 同表铸 token、主后端 192.168.2.77:9031、
 * 用后即删。审计打桩（不打 agentx_action_log，保持库零痕迹）。
 */
@Tag("integration")
class ListMyDraftsIT {

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

    private void mintToken() {
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, tenant_id FROM system_users WHERE username = 'shenbao1' AND deleted = 0 AND status = 0");
        assertThat(users).as("库中应存在启用的 shenbao1").isNotEmpty();
        testUserId = ((Number) users.get(0).get("id")).longValue();
        testTenantId = ((Number) users.get(0).get("tenant_id")).longValue();

        mintedToken = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO system_oauth2_access_token "
                        + "(id, user_id, user_type, user_info, access_token, refresh_token, client_id, scopes, "
                        + "expires_time, creator, updater, tenant_id) "
                        + "VALUES (?, ?, 2, ?, ?, ?, 'huaxi-data-agent', '[]', ?, ?, ?, ?)",
                IdWorker.getId(), testUserId,
                "{\"nickname\":\"ListMyDraftsIT\",\"deptId\":\"0\"}",
                mintedToken, UUID.randomUUID().toString().replace("-", ""),
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)),
                String.valueOf(testUserId), String.valueOf(testUserId), testTenantId);
        System.out.println("[IT] shenbao1: userId=" + testUserId + ", tenantId=" + testTenantId);
    }

    private ListMyDraftsTool newTool(RestActionClient client) {
        ActionAuditLogger quietAudit = new ActionAuditLogger(jdbc.getDataSource()) {
            @Override
            public void log(Entry entry) {
                System.out.println("[IT][audit-muted] tool=" + entry.tool()
                        + " business=" + entry.businessObject());
            }
        };
        return new ListMyDraftsTool(client, quietAudit, new FillContextSupport(client));
    }

    /** shenbao1 名下任一项目名（草稿盘点需项目参数的详情用例取该值） */
    private String anyProjectName(RestActionClient client) {
        RestActionClient.ActionResult r = client.get("/doc/workbench/my-projects",
                FillContextSupport.query("pageSize", "5"), mintedToken, testTenantId);
        assertThat(r.success()).as("查询我的项目应成功").isTrue();
        JsonNode first = r.data().path("list").path(0);
        assertThat(first.path("projectName").asText("")).as("shenbao1 名下应有项目").isNotEmpty();
        return first.path("projectName").asText();
    }

    /** 全量盘点：不传参 = 名下全部进行中项目（跨子阶段草稿检测） */
    @Test
    void overviewAcrossProjects() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        String out = newTool(client).listMyDrafts(null, null,
                String.valueOf(testUserId), mintedToken, String.valueOf(testTenantId), "it-conv");
        System.out.println("[IT] 全量盘点输出（截断 1200 字）：\n"
                + out.substring(0, Math.min(1200, out.length())));
        // 库里有 shenbao1 的真实草稿台账（status=0），应至少盘出 1 份
        assertThat(out).as("应成功返回盘点结果").contains("我的资料填报草稿");
        assertThat(out).as("shenbao1 名下应有草稿").doesNotContain("暂无资料填报草稿");
        assertThat(out).as("条目应含批次号与行数统计").contains("批次 DOC-");
    }

    /** 限定项目盘点 */
    @Test
    void overviewByProject() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        String projectName = anyProjectName(client);
        String out = newTool(client).listMyDrafts(projectName, null,
                String.valueOf(testUserId), mintedToken, String.valueOf(testTenantId), "it-conv");
        System.out.println("[IT] 项目盘点（" + projectName + "）输出首行：\n" + out.split("\n")[0]);
        assertThat(out).containsAnyOf("我的资料填报草稿", "暂无资料填报草稿");
    }

    /** 单子阶段详情：从盘出结果取一个子阶段名展开逐行内容 */
    @Test
    void phaseDraftDetail() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        ListMyDraftsTool tool = newTool(client);
        String overview = tool.listMyDrafts(null, null,
                String.valueOf(testUserId), mintedToken, String.valueOf(testTenantId), "it-conv");
        if (overview.contains("暂无资料填报草稿")) {
            System.out.println("[IT] 无草稿数据，跳过详情用例");
            return;
        }
        // 解析 "- **大阶段 / 子阶段**" 里的子阶段名 + 项目名
        String projectName = extract(overview, "### 项目：", "\n");
        String subName = extract(overview, " / ", "**：");
        assertThat(projectName).as("应解析出项目名").isNotBlank();
        assertThat(subName).as("应解析出子阶段名").isNotBlank();

        String detail = tool.listMyDrafts(projectName, subName,
                String.valueOf(testUserId), mintedToken, String.valueOf(testTenantId), "it-conv");
        System.out.println("[IT] 草稿详情输出（截断 800 字）：\n"
                + detail.substring(0, Math.min(800, detail.length())));
        assertThat(detail).as("应返回草稿详情").contains("草稿详情", "DOC-XM_", "| 节点 | 类别");
    }

    private static String extract(String text, String start, String end) {
        int i = text.indexOf(start);
        if (i < 0) {
            return "";
        }
        int from = i + start.length();
        int j = text.indexOf(end, from);
        return j > from ? text.substring(from, j).trim() : "";
    }
}

package com.llmetor.dataagent.dataagent.action.task;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
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
 * M-E1 读路径集成冒烟（IT，默认不随单测跑，显式 -Dtest=TaskReadIT 执行）：
 * 真实环境跑 listMyTasks / getTaskDetail 两个只读工具（shenbao1 的真实待办）。
 *
 * <p>写路径（stageDraft/resubmitLedger）会真实推进审批流，不在自动 IT 里做——
 * 留给人工验收（演练项目或与 PM 约定即时驳回，计划 §M-E1 验收 3/4/5）。
 *
 * <p>环境与铸 token 通道同 ActionChannelIT（M-E0）：PG 192.168.2.61 同表铸 token、
 * 主后端 192.168.2.77:9031、用后即删。审计打桩（不打 agentx_action_log，保持库零痕迹）。
 */
@Tag("integration")
class TaskReadIT {

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
                "{\"nickname\":\"M-E1-IT\",\"deptId\":\"0\"}",
                mintedToken, UUID.randomUUID().toString().replace("-", ""),
                Timestamp.valueOf(LocalDateTime.now().plusHours(2)),
                String.valueOf(testUserId), String.valueOf(testUserId), testTenantId);
        System.out.println("[IT] shenbao1: userId=" + testUserId + ", tenantId=" + testTenantId);
    }

    @Test
    void listMyTasksAndDetailOnRealData() {
        mintToken();

        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        ActionAuditLogger quietAudit = new ActionAuditLogger(jdbc.getDataSource()) {
            @Override
            public void log(Entry entry) {
                System.out.println("[IT][audit-muted] tool=" + entry.tool() + " action=" + entry.action());
            }
        };
        TaskLedgerSupport support = new TaskLedgerSupport(client);
        ListMyTasksTool listTool = new ListMyTasksTool(client, support, quietAudit);
        GetTaskDetailTool detailTool = new GetTaskDetailTool(client, support, quietAudit);

        String uid = String.valueOf(testUserId);
        String tenant = String.valueOf(testTenantId);

        // ① 待办清单（shenbao1 名下应有真实被驳任务，计划 1.1：3 条）
        String todo = listTool.listMyTasks("todo", uid, mintedToken, tenant, "it-conv");
        System.out.println("[IT] listMyTasks(todo) 输出：\n" + todo);
        assertThat(todo).contains("待办任务");
        assertThat(todo).as("shenbao1 应有待办任务（若为 0 条，先用主平台确认测试数据仍在）")
                .contains("任务ID `");

        // ② 取第一条任务查详情（聚合 + 槽位卡）
        String taskId = extractFirstTaskId(todo);
        assertThat(taskId).isNotBlank();
        String detail = detailTool.getTaskDetail(taskId, uid, mintedToken, tenant, "it-conv");
        System.out.println("[IT] getTaskDetail(" + taskId + ") 输出（截断 2000 字）：\n"
                + detail.substring(0, Math.min(2000, detail.length())));
        assertThat(detail).contains("任务详情", "台账批次", "文件条目");

        // ③ 已办清单（口径可用即可，不强断言条数）
        String done = listTool.listMyTasks("done", uid, mintedToken, tenant, "it-conv");
        System.out.println("[IT] listMyTasks(done) 前 300 字：\n"
                + done.substring(0, Math.min(300, done.length())));
        assertThat(done).contains("已办任务");
    }

    /** 越权/无效任务：不在名下的任务 ID 给人话拒绝（不泄露存在性） */
    @Test
    void unknownTaskRejectedGracefully() {
        mintToken();
        RestActionClient client = new RestActionClient(MAIN_BACKEND, 3000, 15000, 1);
        ActionAuditLogger quietAudit = new ActionAuditLogger(jdbc.getDataSource()) {
            @Override
            public void log(Entry entry) {
            }
        };
        GetTaskDetailTool detailTool = new GetTaskDetailTool(client, new TaskLedgerSupport(client), quietAudit);

        String out = detailTool.getTaskDetail("1", String.valueOf(testUserId), mintedToken,
                String.valueOf(testTenantId), "it-conv");
        System.out.println("[IT] 无效任务输出: " + out);
        assertThat(out).contains("未在您的待办/已办中找到该任务");
    }

    private static String extractFirstTaskId(String todo) {
        int i = todo.indexOf("任务ID `");
        if (i < 0) {
            return "";
        }
        int start = i + "任务ID `".length();
        int end = todo.indexOf('`', start);
        return end > start ? todo.substring(start, end) : "";
    }
}

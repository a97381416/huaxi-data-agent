package com.llmetor.dataagent.dataagent.action;

import com.llmetor.dataagent.dataagent.action.task.ActionPlanCache;
import com.llmetor.dataagent.dataagent.action.task.CancelConfirmTool;
import com.llmetor.dataagent.dataagent.action.task.GetTaskDetailTool;
import com.llmetor.dataagent.dataagent.action.task.ListMyTasksTool;
import com.llmetor.dataagent.dataagent.action.task.PatchMerger;
import com.llmetor.dataagent.dataagent.action.task.ResubmitLedgerTool;
import com.llmetor.dataagent.dataagent.action.task.StageDraftTool;
import com.llmetor.dataagent.dataagent.action.task.TaskLedgerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * M-E1 任务处理组工具全链路单测（MockRestServiceServer，样式对齐 RestActionClientTest）。
 *
 * <p>覆盖：任务定位/越权、detail 聚合 + 上传槽位卡、stageDraft 全量组装与请求体断言、
 * resubmitLedger 首轮出计划【零写请求】、二轮 stage-draft→resubmit 顺序与请求体、
 * 令牌生命周期（一次性/现状漂移重出卡/取消作废）、权限拒绝短路、状态守卫。
 *
 * <p>本测试位于 action 包以复用 {@link RestActionClient} 的包级测试构造；
 * 权限与审计用匿名子类打桩（不打真库）。
 */
class TaskActionToolsTest {

    private static final String BASE = "http://main.test/admin-api";
    private static final String TOKEN = "tok123";
    private static final String USER = "1";
    private static final String TENANT = "1";
    private static final String CONV = "conv-1";
    private static final String TASK_ID = "2114965912345678001";
    private static final String INSTANCE_ID = "3114965912345678002";
    private static final Pattern CT_PATTERN = Pattern.compile("ct_[0-9a-f]{16}");

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestActionClient client;
    private ListMyTasksTool listTool;
    private GetTaskDetailTool detailTool;
    private StageDraftTool stageTool;
    private ResubmitLedgerTool resubmitTool;
    private CancelConfirmTool cancelTool;
    private ActionPlanCache planCache;

    // ── 装配（权限放行/审计静默打桩） ──────────────────────────────────────────

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestActionClient(builder, BASE, 0);
        planCache = new ActionPlanCache(300);
        ConfirmTokenService confirmTokens = new ConfirmTokenService(300); // resubmit/cancel 必须共享同一实例
        TaskLedgerSupport support = new TaskLedgerSupport(client);
        ActionAuditLogger quietAudit = new ActionAuditLogger(h2()) {
            @Override
            public void log(Entry entry) {
                // 测试不打库
            }
        };
        FunctionPermissionChecker allow = permissionChecker(null);
        listTool = new ListMyTasksTool(client, support, quietAudit);
        detailTool = new GetTaskDetailTool(client, support, quietAudit);
        stageTool = new StageDraftTool(allow, client, support, new PatchMerger(), quietAudit);
        resubmitTool = new ResubmitLedgerTool(allow, client, support, new PatchMerger(),
                confirmTokens, planCache, quietAudit,
                new com.llmetor.dataagent.dataagent.action.fill.FillContextSupport(client));
        cancelTool = new CancelConfirmTool(confirmTokens, planCache,
                new com.llmetor.dataagent.dataagent.action.review.ReviewPlanCache(300), quietAudit);
    }

    private FunctionPermissionChecker permissionChecker(String denyMessage) {
        return new FunctionPermissionChecker(h2(), 30000) {
            @Override
            public String checkPermission(Long userId, String permission) {
                return denyMessage;
            }
        };
    }

    /** H2 内存库：让 M-E0 组件的 JdbcTemplate 构造可用（方法全被覆写，不会真查） */
    private static DataSource h2() {
        return new DriverManagerDataSource("jdbc:h2:mem:me1tools" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    // ── JSON 夹具 ─────────────────────────────────────────────────────────────

    private static String ok(String dataJson) {
        return "{\"code\":0,\"data\":" + dataJson + ",\"msg\":\"\"}";
    }

    private static final String TASK_JSON = "{\"id\":\"" + TASK_ID + "\",\"instanceId\":\"" + INSTANCE_ID
            + "\",\"businessId\":\"42\",\"nodeType\":\"submitter\","
            + "\"title\":\"南三高速公路项目_建设准备_前期审批的审核任务\",\"categoryLabel\":\"文档收集\","
            + "\"approveStatus\":2,\"partialFlag\":false,\"createTime\":\"2026-11-08 10:00:00\"}";

    /** 条目：9001 项目建议书(被驳+待上传)、9002 可行性研究(被驳)、9003 其他合同(已通过锁定) */
    private static final String E1 = "{\"id\":\"9001\",\"nodeId\":\"7\",\"nodeCode\":\"LX\","
            + "\"category\":\"项目建议书\",\"fileName\":\"jianyi.pdf\",\"fileUrl\":\"doc/jianyi.pdf\","
            + "\"configId\":5,\"notApplicable\":-1,\"auditStatus\":3,\"pmOpinion\":\"需盖章版\","
            + "\"summary\":\"\",\"uploader\":\"1\",\"uploadTime\":\"2026-11-07 09:00:00\",\"source\":1}";
    private static final String E2 = "{\"id\":\"9002\",\"nodeId\":\"7\",\"nodeCode\":\"LX\","
            + "\"category\":\"可行性研究\",\"fileName\":\"kexing.pdf\",\"notApplicable\":0,"
            + "\"auditStatus\":3,\"pmOpinion\":\"\",\"summary\":\"\"}";
    private static final String E3_LOCKED = "{\"id\":\"9003\",\"nodeId\":\"8\",\"nodeCode\":\"HT\","
            + "\"category\":\"其他合同\",\"notApplicable\":0,\"auditStatus\":2}";
    /** 现状漂移夹具：9001 消失、9004（合同文件/项目建议书）出现 */
    private static final String E4_DRIFT = "{\"id\":\"9004\",\"nodeId\":\"8\",\"nodeCode\":\"HT\","
            + "\"category\":\"项目建议书\",\"notApplicable\":0,\"auditStatus\":3}";

    private static String ledgerJson(String status, String entries7, String entries8) {
        return "{\"id\":42,\"projectId\":2083075480264298498,\"phaseId\":2056681503596388354,"
                + "\"projectName\":\"南三高速公路项目\",\"stageName\":\"建设准备\","
                + "\"phaseName\":\"前期审批\",\"docNumber\":\"DOC-QZ-100001-002\",\"status\":" + status
                + ",\"currentInstanceId\":\"" + INSTANCE_ID + "\",\"nodeGroups\":["
                + "{\"nodeId\":\"7\",\"nodeCode\":\"LX\",\"nodeName\":\"立项文件\",\"fileEntries\":[" + entries7 + "]},"
                + "{\"nodeId\":\"8\",\"nodeCode\":\"HT\",\"nodeName\":\"合同文件\",\"fileEntries\":[" + entries8 + "]}"
                + "]}";
    }

    private static final String LEDGER_REJECTED =
            ledgerJson("3", E1 + "," + E2, E3_LOCKED);
    private static final String LEDGER_REVIEWING =
            ledgerJson("1", E1 + "," + E2, E3_LOCKED);
    private static final String LEDGER_DRIFTED =
            ledgerJson("3", E2, E3_LOCKED + "," + E4_DRIFT);

    // ── 期望辅助（按调用顺序逐条声明）─────────────────────────────────────────

    private void expectTaskLookups(String ledgerJson) {
        server.expect(requestTo(BASE + "/task-center/task/todo-page?pageNo=1&pageSize=200"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"list\":[" + TASK_JSON + "],\"total\":1}"), MediaType.APPLICATION_JSON));
        expectLedgerGet(ledgerJson);
    }

    private void expectLedgerGet(String ledgerJson) {
        server.expect(requestTo(BASE + "/doc/ledger/get?id=42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(ledgerJson), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/doc/ledger/get-stage-draft?ledgerId=42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("[]"), MediaType.APPLICATION_JSON));
    }

    private void expectPut(String path) {
        server.expect(requestTo(BASE + path))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(ok("null"), MediaType.APPLICATION_JSON));
    }

    private static String extractToken(String toolResult) {
        Matcher m = CT_PATTERN.matcher(toolResult);
        assertThat(m.find()).as("结果中应包含确认令牌").isTrue();
        return m.group();
    }

    // ── 读工具 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listMyTasks：待办富化批次号/被驳条数")
    void listMyTasksEnriches() {
        server.expect(requestTo(BASE + "/task-center/task/todo-page?pageNo=1&pageSize=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("{\"list\":[" + TASK_JSON + "],\"total\":1}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/doc/ledger/get?id=42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(LEDGER_REJECTED), MediaType.APPLICATION_JSON));

        String out = listTool.listMyTasks("todo", USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("待办任务共 1 条", "DOC-QZ-100001-002", "已驳回", "被驳 2 条", TASK_ID);
        server.verify();
    }

    @Test
    @DisplayName("getTaskDetail：聚合明细+审批轨迹（文件条目表含被驳/待传行，锁定行标注）")
    void getTaskDetailAggregates() {
        expectTaskLookups(LEDGER_REJECTED);
        server.expect(requestTo(BASE + "/task-center/task/list-by-instance?instanceId=" + INSTANCE_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok("[{\"assigneeName\":\"张三\",\"approveStatus\":2,"
                        + "\"opinion\":\"全部需盖章版\",\"endTime\":\"2026-11-08 10:00:00\"}]"), MediaType.APPLICATION_JSON));

        String out = detailTool.getTaskDetail(TASK_ID, USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("任务详情", "DOC-QZ-100001-002", "需盖章版", "张三", "审批轨迹");
        assertThat(out).contains("🔒"); // 图例 + 锁定行标注
        assertThat(out).doesNotContain("__card"); // 槽位卡已下线，不再追加卡片 JSON
        server.verify();
    }

    // ── stageDraft（W1）──────────────────────────────────────────────────────

    @Test
    @DisplayName("stageDraft：增量合并成全量表单提交（含锁定行原样、流程字段剔除）")
    void stageDraftSendsFullForm() {
        expectTaskLookups(LEDGER_REJECTED);
        server.expect(requestTo(BASE + "/doc/ledger/stage-draft"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.ledgerId").value("42"))
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.fileEntries.length()").value(3))
                .andExpect(jsonPath("$.fileEntries[0].id").value("9001"))
                .andExpect(jsonPath("$.fileEntries[0].notApplicable").value(2))
                .andExpect(jsonPath("$.fileEntries[0].auditStatus").doesNotExist())
                .andExpect(jsonPath("$.fileEntries[0].pmOpinion").doesNotExist())
                .andExpect(jsonPath("$.fileEntries[0].uploader").doesNotExist())
                .andExpect(jsonPath("$.fileEntries[0].uploadTime").doesNotExist())
                .andExpect(jsonPath("$.fileEntries[1].id").value("9002"))
                .andExpect(jsonPath("$.fileEntries[1].notApplicable").value(0))
                .andExpect(jsonPath("$.fileEntries[2].id").value("9003"))
                .andExpect(jsonPath("$.fileEntries[2].auditStatus").doesNotExist())
                .andRespond(withSuccess(ok("null"), MediaType.APPLICATION_JSON));

        String out = stageTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("已暂存 1 处", "待上传 → 不涉及", "未提交", "resubmitLedger");
        server.verify();
    }

    @Test
    @DisplayName("stageDraft 闸拒绝：锁定行/白名单外字段——不产生任何写请求")
    void stageDraftGatesRejectWithoutWrite() {
        // 两次调用各拉一轮现状（共 6 个 GET），无 PUT
        expectTaskLookups(LEDGER_REJECTED);
        expectTaskLookups(LEDGER_REJECTED);

        String locked = stageTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("其他合同", null, null, "summary", "x")),
                USER, TOKEN, TENANT, CONV);
        String whitelist = stageTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "pmOpinion", "好")),
                USER, TOKEN, TENANT, CONV);

        assertThat(locked).contains("未通过校验").contains("已通过");
        assertThat(whitelist).contains("项目经理意见").contains("不能修改");
        server.verify(); // 无未消费的 PUT 期望
    }

    @Test
    @DisplayName("状态守卫：台账非驳回/部分通过状态拒绝暂存")
    void stageDraftStatusGuard() {
        expectTaskLookups(LEDGER_REVIEWING);

        String out = stageTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("只有「已驳回/部分通过」状态");
        server.verify();
    }

    // ── resubmitLedger（W2 两段式）────────────────────────────────────────────

    @Test
    @DisplayName("resubmit 首轮：只出计划+令牌+确认卡，零写请求")
    void resubmitFirstRoundPlanOnly() {
        expectTaskLookups(LEDGER_REJECTED);

        String out = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("尚未执行", "待上传", "不涉及", "审批中", "审核人", "ct_");
        assertThat(out).doesNotContain("__card"); // 确认卡已下线，纯对话呈现
        server.verify(); // 无 PUT
    }

    @Test
    @DisplayName("resubmit 首轮不传审核人：默认取常用审核人第1名并在计划标注")
    void resubmitDefaultAssigneeFromRecommend() {
        expectTaskLookups(LEDGER_REJECTED);
        server.expect(requestTo(BASE + "/doc/recommend/assignees?projectId=2083075480264298498"
                        + "&subStageId=2056681503596388354&limit=8&category=doc_collect"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ok(
                        "[{\"userId\":\"8001\",\"nickname\":\"张三\",\"deptName\":\"工程部\","
                                + "\"selectedCount\":8,\"actedCount\":3},"
                                + "{\"userId\":\"8002\",\"nickname\":\"李四\",\"deptName\":\"工程部\"}]"),
                        MediaType.APPLICATION_JSON));

        String out = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                null, null, USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("默认，常用审核人第 1 名", "张三(ID:8001)", "8001");
        assertThat(out).contains("备选常用审核人", "李四(ID:8002)");
        assertThat(out).contains("换审核人"); // 换人指引
        server.verify(); // 无 PUT
    }

    @Test
    @DisplayName("resubmit 换人重出计划：同任务旧令牌自动作废（防拿旧审核人提交）")
    void resubmitNewPlanInvalidatesOldToken() {
        expectTaskLookups(LEDGER_REJECTED); // 计划A（审核人9001）
        expectTaskLookups(LEDGER_REJECTED); // 计划B（换审核人8002）

        String planA = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);
        String tokenA = extractToken(planA);
        String planB = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("8002"), null, USER, TOKEN, TENANT, CONV);
        String tokenB = extractToken(planB);
        assertThat(tokenB).isNotEqualTo(tokenA);

        // 旧令牌 A：计划已被 B 取代 → 零 HTTP 直接拒绝，绝不会带旧审核人执行
        String stale = resubmitTool.resubmitLedger(TASK_ID, null, null, tokenA, USER, TOKEN, TENANT, CONV);
        assertThat(stale).contains("已被更新的计划取代");
        server.verify();
    }

    @Test
    @DisplayName("resubmit 二轮：先 stage-draft 刷最终版再 resubmit（顺序+请求体）")
    void resubmitSecondRoundExecutes() {
        // 全部期望前置（有序服务器）：首轮 3 GET + 二轮 3 GET + PUT stage-draft + PUT resubmit
        expectTaskLookups(LEDGER_REJECTED);
        expectTaskLookups(LEDGER_REJECTED);
        server.expect(requestTo(BASE + "/doc/ledger/stage-draft"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.fileEntries.length()").value(3))
                .andExpect(jsonPath("$.fileEntries[0].notApplicable").value(2))
                .andRespond(withSuccess(ok("null"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/doc/ledger/resubmit"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.ledgerId").value("42"))
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.needNext").value(true))
                .andExpect(jsonPath("$.nextNodes[0].nodeName").value("部门审核"))
                .andExpect(jsonPath("$.nextNodes[0].approvalStrategy").value("countersign"))
                .andExpect(jsonPath("$.nextNodes[0].assigneeIds[0]").value(9001))
                .andExpect(jsonPath("$.fileEntries.length()").value(3))
                .andExpect(jsonPath("$.fileEntries[0].notApplicable").value(2))
                .andRespond(withSuccess(ok("null"), MediaType.APPLICATION_JSON));

        String plan = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);
        String token = extractToken(plan);
        String out = resubmitTool.resubmitLedger(TASK_ID, null, null, token, USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("重新提交完成", "审批中", "待办已送达", "9001");
        server.verify();
    }

    @Test
    @DisplayName("resubmit 令牌一次性：执行成功后再用同令牌 → 无效（且不再发 REST）")
    void resubmitTokenSingleUse() {
        expectTaskLookups(LEDGER_REJECTED);
        expectTaskLookups(LEDGER_REJECTED);
        expectPut("/doc/ledger/stage-draft");
        expectPut("/doc/ledger/resubmit");

        String plan = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);
        String token = extractToken(plan);
        assertThat(resubmitTool.resubmitLedger(TASK_ID, null, null, token, USER, TOKEN, TENANT, CONV))
                .contains("重新提交完成");

        // 计划缓存已随执行清除：再次携带同令牌立即被拒，无任何 HTTP
        String again = resubmitTool.resubmitLedger(TASK_ID, null, null, token, USER, TOKEN, TENANT, CONV);
        assertThat(again).contains("确认令牌无效");
        server.verify();
    }

    @Test
    @DisplayName("resubmit 现状漂移：重解析哈希不一致 → 令牌作废、重出计划（零写请求）")
    void resubmitStateDriftReissuesPlan() {
        expectTaskLookups(LEDGER_REJECTED);
        // 现状漂移：9001 消失、9004 同名类别出现 → 解析结果 entryId 变 → 哈希不匹配
        expectTaskLookups(LEDGER_DRIFTED);

        String plan = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);
        String token = extractToken(plan);
        String out = resubmitTool.resubmitLedger(TASK_ID, null, null, token, USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("已失效").contains("ct_").doesNotContain("__card");
        String newToken = extractToken(out);
        assertThat(newToken).isNotEqualTo(token);
        assertThat(out).doesNotContain("重新提交完成");
        server.verify(); // 无 PUT
    }

    @Test
    @DisplayName("cancelConfirm：作废令牌与计划，后续二轮执行被拒")
    void cancelConfirmInvalidatesPlan() {
        expectTaskLookups(LEDGER_REJECTED);
        String plan = resubmitTool.resubmitLedger(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                List.of("9001"), null, USER, TOKEN, TENANT, CONV);
        String token = extractToken(plan);
        assertThat(token).startsWith("ct_");

        assertThat(cancelTool.cancelConfirm(token, USER, TOKEN, TENANT, CONV))
                .contains("已取消", "未产生任何变更");

        String out = resubmitTool.resubmitLedger(TASK_ID, null, null, token, USER, TOKEN, TENANT, CONV);
        assertThat(out).contains("确认令牌无效");
        server.verify(); // 取消与被拒的二轮均无写请求
    }

    // ── 权限/越权短路 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("权限拒绝：写工具在本地权限闸即短路（零 REST）")
    void permissionDeniedShortCircuits() {
        RestActionClient used = new RestActionClient(builder, BASE, 0);
        StageDraftTool deniedTool = new StageDraftTool(permissionChecker("❌ " + FunctionPermissionChecker.DENY_MESSAGE),
                used, new TaskLedgerSupport(used), new PatchMerger(),
                new ActionAuditLogger(h2()) {
                    @Override
                    public void log(Entry entry) {
                    }
                });

        String out = deniedTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("权限");
        server.verify(); // 未发任何请求
    }

    @Test
    @DisplayName("越权/不存在任务：todo/done 均无此任务 → 人话拒绝")
    void taskNotInMyListRejected() {
        String empty = ok("{\"list\":[],\"total\":0}");
        server.expect(requestTo(BASE + "/task-center/task/todo-page?pageNo=1&pageSize=200"))
                .andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/task-center/task/done-page?pageNo=1&pageSize=200"))
                .andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));

        String out = stageTool.stageDraft(TASK_ID,
                List.of(new PatchMerger.PatchSpec("项目建议书", null, null, "notApplicable", "2")),
                USER, TOKEN, TENANT, CONV);

        assertThat(out).contains("未在您的待办/已办中找到该任务");
        server.verify();
    }
}

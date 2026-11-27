package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.action.UnknownOutcomeException;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.llmetor.miniagentx.agent.ToolExecutionContext;
import com.llmetor.miniagentx.interrupt.HitlParkException;
import com.llmetor.miniagentx.model.RunnableParams;

import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withGatewayTimeout;

/**
 * 三期 HITL 单元测试：覆盖收集型（QUESTION）、核对型（VERIFY_OUTCOME）、计划确认型（PLAN_CONFIRM）与待办排序。
 */
class HitlPhase3Test {

    private JdbcTemplate jdbc;
    private DecisionService decisionService;
    private HitlToolHelper hitlToolHelper;
    private AskUserQuestionTool askTool;
    private ConfirmPlanTool planTool;
    private HitlProperties props;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:hitl3_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS agentx_conversation (conversation_id varchar(100), question text, created_at timestamp)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_decision_grant (
                  grant_id varchar(64) NOT NULL PRIMARY KEY,
                  user_id bigint NOT NULL,
                  conversation_id varchar(100) NOT NULL,
                  source_decision_id varchar(64) NOT NULL,
                  tool_name varchar(100) NOT NULL,
                  scope_key varchar(255) NOT NULL,
                  scope_desc varchar(500) DEFAULT NULL,
                  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
                  max_uses int NOT NULL DEFAULT 20,
                  used_count int NOT NULL DEFAULT 0,
                  expires_at timestamp NOT NULL,
                  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  revoked_at timestamp DEFAULT NULL
                )
                """);

        props = new HitlProperties();
        props.setInlineAwaitSeconds(5);
        ObjectMapper mapper = new ObjectMapper();
        DecisionAwaitRegistry registry = new DecisionAwaitRegistry();
        registry.start();
        decisionService = new DecisionService(ds, mapper, new ConfirmTokenService(300), props, registry);
        hitlToolHelper = new HitlToolHelper(decisionService, mapper, null, "http://127.0.0.1:3000");
        askTool = new AskUserQuestionTool(decisionService);
        planTool = new ConfirmPlanTool(decisionService);
    }

    @Test
    void questionSubmit_returnsAnswers_andNeverWritesGrantTable() throws Exception {
        CompletableFuture<String> toolFuture = CompletableFuture.supplyAsync(() -> askTool.askUserQuestion(
                List.of(
                        new AskUserQuestionTool.QuestionItem("Q1", "报告面向对象？", "single", List.of("院领导", "科室主任"), true),
                        new AskUserQuestionTool.QuestionItem("Q2", "关注指标？", "multiple", List.of("预算执行率", "异常预警"), false)
                ),
                "经费分析报告需求澄清",
                "1001",
                "conv-q-1"
        ));

        HitlCardPayload pending = waitForPending("conv-q-1");
        assertEquals(TYPE_QUESTION, pending.type());
        assertFalse(pending.allowSessionGrant());

        // 安全红线：收集型绝不允许使用 ALLOW_SESSION
        assertThrows(ResponseStatusException.class, () -> decisionService.decide(
                pending.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_ALLOW_SESSION, pending.revision(), "cmd-bad", null, null)));

        DecideResponse resp = decisionService.decide(
                pending.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_SUBMIT_ANSWERS, pending.revision(), "cmd-q-1", null,
                        Map.of("Q1", "院领导", "Q2", List.of("预算执行率", "异常预警"))));
        assertEquals(STATUS_ANSWERED, resp.status());
        assertEquals(MODE_INLINE_RESUMED, resp.mode());

        String toolOut = toolFuture.get();
        assertTrue(toolOut.contains("院领导"));
        assertTrue(toolOut.contains("预算执行率"));

        Integer grantCount = jdbc.queryForObject("SELECT COUNT(*) FROM agentx_decision_grant", Integer.class);
        assertEquals(0, grantCount);
    }

    @Test
    void questionSoftTimeout_doesNotParkSession() {
        props.setInlineAwaitSeconds(1);
        String out = askTool.askUserQuestion(
                List.of(new AskUserQuestionTool.QuestionItem("Q1", "统计周期？", "single", List.of("本季度", "本年度"), true)),
                null, "1001", "conv-q-timeout"
        );
        assertTrue(out.contains("60 秒内未作答") || out.contains("默认假设"));
        List<HitlCardPayload> all = decisionService.listByConversation(1001L, "conv-q-timeout");
        assertEquals(1, all.size());
        assertEquals(STATUS_TIMED_OUT_SOFT, all.get(0).status());
    }

    @Test
    void restActionClient_distinguishesConnectVsReadTimeout() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestActionClient client = new RestActionClient(builder, "http://127.0.0.1:9031/admin-api", 0);

        server.expect(requestTo("http://127.0.0.1:9031/admin-api/doc/ledger/submit-for-review"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withGatewayTimeout());

        assertThrows(UnknownOutcomeException.class, () ->
                client.post("/doc/ledger/submit-for-review", Map.of("id", 1), "tok", 1L));
        server.verify();

        // ConnectException / UnknownHostException / NoRouteToHostException / SSLHandshakeException -> 未发送
        assertFalse(RestActionClient.isUnknownOutcomeCause(new ConnectException("Connection refused")));
        assertFalse(RestActionClient.isUnknownOutcomeCause(new UnknownHostException("api.example.com")));
        assertFalse(RestActionClient.isUnknownOutcomeCause(new NoRouteToHostException("No route to host")));
        assertFalse(RestActionClient.isUnknownOutcomeCause(new SSLHandshakeException("PKIX path building failed")));
        assertFalse(RestActionClient.isUnknownOutcomeCause(new SocketTimeoutException("connect timed out")));
        // Read timed out / EOF / Connection reset -> 已发送结果未知，抛 UnknownOutcomeException
        assertTrue(RestActionClient.isUnknownOutcomeCause(new SocketTimeoutException("Read timed out")));
        assertTrue(RestActionClient.isUnknownOutcomeCause(new EOFException("Unexpected EOF")));
        assertTrue(RestActionClient.isUnknownOutcomeCause(new SocketException("Connection reset")));
    }

    @Test
    void verifyOutcome_confirmedSucceededVsNotAppliedRetry() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() ->
                hitlToolHelper.executeWithVerifyOutcome("submitLedgerForReview", "提交台账审核", "L-20260901",
                        1001L, "conv-v-1",
                        () -> {
                            int n = callCount.incrementAndGet();
                            if (n == 1) {
                                throw new UnknownOutcomeException("POST", "/doc/ledger/submit-for-review",
                                        "2026-11-26 14:05:12", "Read timed out", 0, new SocketTimeoutException());
                            }
                            return "OK-RETRY";
                        },
                        res -> "SUCCESS:" + res));

        HitlCardPayload pending1 = waitForPending("conv-v-1");
        assertEquals(TYPE_VERIFY_OUTCOME, pending1.type());
        decisionService.decide(pending1.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_CONFIRMED_SUCCEEDED, pending1.revision(), "cmd-v-1", null, null));
        String out1 = f1.get();
        assertTrue(out1.contains("主平台已生效"));
        assertEquals(1, callCount.get()); // 严禁重发

        // 场景2：确认未生效 -> 重发 1 次
        callCount.set(0);
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() ->
                hitlToolHelper.executeWithVerifyOutcome("submitLedgerForReview", "提交台账审核", "L-20260902",
                        1001L, "conv-v-2",
                        () -> {
                            int n = callCount.incrementAndGet();
                            if (n == 1) {
                                throw new UnknownOutcomeException("POST", "/doc/ledger/submit-for-review",
                                        "2026-11-26 14:06:00", "HTTP 504", 504, null);
                            }
                            return "OK-RETRY";
                        },
                        res -> "SUCCESS:" + res));

        HitlCardPayload pending2 = waitForPending("conv-v-2");
        decisionService.decide(pending2.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_CONFIRMED_NOT_APPLIED, pending2.revision(), "cmd-v-2", null, null));
        String out2 = f2.get();
        assertTrue(out2.contains("SUCCESS:OK-RETRY"));
        assertEquals(2, callCount.get()); // 精确重发 1 次
    }

    @Test
    void confirmPlan_reviseThenApprove_andUrgencySorting() throws Exception {
        CompletableFuture<String> fPlan = CompletableFuture.supplyAsync(() ->
                planTool.confirmPlan("5个项目批量校对", "1. 项目A\n2. 项目B\n3. 项目C", 3, "1001", "conv-p-1"));

        HitlCardPayload pending = waitForPending("conv-p-1");
        assertEquals(TYPE_PLAN_CONFIRM, pending.type());
        decisionService.decide(pending.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_REVISE_PLAN, pending.revision(), "cmd-p-1", "去掉项目C", null));
        String out = fPlan.get();
        assertTrue(out.contains("去掉项目C"));

        // 校验全局待办按紧急度排序：VERIFY_OUTCOME > PERMISSION > PLAN_CONFIRM > QUESTION
        decisionService.createOrAutoApprove(new CreateDecisionRequest(
                "c1", "c1", null, null, 2001L, TYPE_QUESTION, "ask_user_question",
                null, null, false, "提问", "Q", List.of(), "Q", Map.of(), "h1", 10));
        decisionService.createOrAutoApprove(new CreateDecisionRequest(
                "c2", "c2", null, null, 2001L, TYPE_PLAN_CONFIRM, "confirm_plan",
                null, null, false, "计划", "P", List.of(), "P", Map.of(), "h2", 10));
        decisionService.createOrAutoApprove(new CreateDecisionRequest(
                "c3", "c3", null, null, 2001L, TYPE_PERMISSION, "approveTask",
                null, null, false, "授权", "A", List.of(), "A", Map.of(), "h3", 10));
        decisionService.createOrAutoApprove(new CreateDecisionRequest(
                "c4", "c4", null, null, 2001L, TYPE_VERIFY_OUTCOME, "submitLedgerForReview",
                null, null, false, "核对", "V", List.of(), "V", Map.of(), "h4", 10));

        List<HitlCardPayload> ordered = decisionService.listPending(2001L, null);
        assertEquals(4, ordered.size());
        assertEquals(TYPE_VERIFY_OUTCOME, ordered.get(0).type());
        assertEquals(TYPE_PERMISSION, ordered.get(1).type());
        assertEquals(TYPE_PLAN_CONFIRM, ordered.get(2).type());
        assertEquals(TYPE_QUESTION, ordered.get(3).type());
    }

    @Test
    void verifyOutcome_slowPathParkAndResume_succeededAndRetry() {
        props.setInlineAwaitSeconds(1);
        AtomicInteger calls = new AtomicInteger(0);

        // 1. 首次调用超时并超 1s 未核对 -> 抛出 HitlParkException 挂起
        HitlParkException parkEx = assertThrows(HitlParkException.class, () ->
                hitlToolHelper.executeWithVerifyOutcome("submitLedgerForReview", "提交台账审核", "L-PARK-01",
                        1001L, "conv-v-park",
                        () -> {
                            calls.incrementAndGet();
                            throw new UnknownOutcomeException("POST", "/doc/ledger/submit-for-review",
                                    "2026-11-27 10:00:00", "Read timed out", 0, new SocketTimeoutException("Read timed out"));
                        },
                        res -> "OK"));
        String decId = parkEx.getDecisionId();
        assertNotNull(decId);

        // 2. 挂起后慢路径核对：主平台已生效 -> 短路收口且严禁重发
        DecisionEntity pending = decisionService.findById(decId);
        DecideResponse resp1 = decisionService.decide(decId, 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_CONFIRMED_SUCCEEDED, pending.revision(), "cmd-park-1", null, null));
        assertEquals(MODE_NEED_RESUME, resp1.mode());

        RunnableParams rp = RunnableParams.builder().conversationId("conv-v-park").userId("1001").build();
        ToolExecutionContext.set(rp, "conv-v-park", "tc-1", null, null, decId, null, null);
        try {
            String shortCircuit = hitlToolHelper.checkVerifyOutcomeShortCircuit("submitLedgerForReview");
            assertNotNull(shortCircuit);
            assertTrue(shortCircuit.contains("主平台已生效"));
            assertEquals(1, calls.get());
        } finally {
            ToolExecutionContext.clear();
        }

        // 3. 另一单挂起后选择「确认未生效，重新执行」-> 放行并精确重发 1 次
        HitlParkException parkEx2 = assertThrows(HitlParkException.class, () ->
                hitlToolHelper.executeWithVerifyOutcome("approveTask", "审核通过", "T-PARK-02",
                        1001L, "conv-v-park2",
                        () -> {
                            calls.incrementAndGet();
                            throw new UnknownOutcomeException("POST", "/doc/ledger-process/approve",
                                    "2026-11-27 10:01:00", "HTTP 504", 504, null);
                        },
                        res -> "APPROVED:" + res));
        String decId2 = parkEx2.getDecisionId();
        DecisionEntity pending2 = decisionService.findById(decId2);
        decisionService.decide(decId2, 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_CONFIRMED_NOT_APPLIED, pending2.revision(), "cmd-park-2", null, null));

        RunnableParams rp2 = RunnableParams.builder().conversationId("conv-v-park2").userId("1001").build();
        ToolExecutionContext.set(rp2, "conv-v-park2", "tc-2", null, null, decId2, null, null);
        try {
            assertNull(hitlToolHelper.checkVerifyOutcomeShortCircuit("approveTask"));
            assertTrue(hitlToolHelper.isResumingVerifyRetry("approveTask"));
            String out = hitlToolHelper.executeWithVerifyOutcome("approveTask", "审核通过", "T-PARK-02",
                    1001L, "conv-v-park2",
                    () -> {
                        calls.incrementAndGet();
                        return "DONE";
                    },
                    res -> "APPROVED:" + res);
            assertTrue(out.contains("APPROVED:DONE"));
            assertEquals(3, calls.get());
        } finally {
            ToolExecutionContext.clear();
        }
    }

    @Test
    void decide_conflictDoesNotLeakGrant_andMetricsIncludeFastSlowRates() throws Exception {
        CreateResult created = decisionService.createOrAutoApprove(new CreateDecisionRequest(
                "conv-grant-1", "conv-grant-1", null, null, 3001L,
                TYPE_PERMISSION, "submitLedgerForReview",
                "submitLedgerForReview:SUBMIT:project=P1", "仅限 P1", true,
                "提审", "P1", List.of(), "提审 P1", Map.of("p", 1), "h-g1", 30));
        String decId = created.entity().decisionId();

        // 乐观锁版本冲突（revision=999）必须回滚，不能残留 grant 记录
        assertThrows(ResponseStatusException.class, () -> decisionService.decide(
                decId, 3001L, "李四", "127.0.0.1",
                new DecideRequest(OPTION_ALLOW_SESSION, 999, "cmd-bad-rev", null, null)));
        Integer grantCount = jdbc.queryForObject("SELECT COUNT(*) FROM agentx_decision_grant", Integer.class);
        assertEquals(0, grantCount);

        // 正常提交 ALLOW_SESSION（模拟快路径唤醒）
        CompletableFuture<DecisionAwaitRegistry.AwaitResult> waiter = CompletableFuture.supplyAsync(() ->
                decisionService.awaitDecision(decId, false));
        Thread.sleep(60);
        DecideResponse okResp = decisionService.decide(
                decId, 3001L, "李四", "127.0.0.1",
                new DecideRequest(OPTION_ALLOW_SESSION, 1, "cmd-ok-g1", null, null));
        assertEquals(MODE_INLINE_RESUMED, okResp.mode());
        assertNotNull(okResp.grantId());
        assertEquals(DecisionAwaitRegistry.AwaitState.APPROVED, waiter.get().state());

        // commandId 幂等重放应保留 INLINE_RESUMED 模式
        DecideResponse replayResp = decisionService.decide(
                decId, 3001L, "李四", "127.0.0.1",
                new DecideRequest(OPTION_ALLOW_SESSION, 1, "cmd-ok-g1", null, null));
        assertEquals(MODE_INLINE_RESUMED, replayResp.mode());

        // 验证监控指标聚合包含快慢路径统计
        Map<String, Object> metrics = decisionService.getHitlMetrics(24);
        assertTrue(((Number) metrics.get("total")).longValue() >= 1L);
        assertTrue(((Number) metrics.get("inlineCount")).longValue() >= 1L);
        assertNotNull(metrics.get("inlineRate"));
        assertNotNull(metrics.get("slowPathRate"));

        // 验证 buildResumePrompt 覆盖三期所有终态
        DecisionEntity qAns = new DecisionEntity(
                "d1", "c1", "s1", null, null, 1L, TYPE_QUESTION, STATUS_ANSWERED, 2,
                "ask_user_question", null, "提问", "{}", "h", null, OPTION_SUBMIT_ANSWERS,
                "{\"Q1\":\"A\"}", null, null, 1L, "U", null, "c", null, null, null, null, MODE_NEED_RESUME);
        assertTrue(decisionService.buildResumePrompt(qAns).contains("答案 JSON"));

        DecisionEntity pApp = new DecisionEntity(
                "d2", "c1", "s1", null, null, 1L, TYPE_PLAN_CONFIRM, STATUS_APPROVED, 2,
                "confirm_plan", null, "计划", "{}", "h", null, OPTION_APPROVE_PLAN,
                null, null, null, 1L, "U", null, "c", null, null, null, null, MODE_NEED_RESUME);
        assertFalse(decisionService.buildResumePrompt(pApp).contains("confirmToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void questionRequired_fastPathAndOptionDescriptions_andSlowPathParkResume() throws Exception {
        // 1. required=true + optionDescriptions 快路径作答
        Map<String, Object> descs = Map.of(
                "1012测试 / 前期配套文件", "草稿批次 DOC-202611 · 已填 2/8 项",
                "南三高速公路 / 合同管理", "草稿批次 DOC-202610 · 已填 5/6 项");
        CompletableFuture<String> fastFuture = CompletableFuture.supplyAsync(() -> askTool.askUserQuestion(
                List.of(new AskUserQuestionTool.QuestionItem(
                        "Q1", "请选择要填报的草稿：", "single",
                        List.of("1012测试 / 前期配套文件", "南三高速公路 / 合同管理"),
                        descs, true)),
                "请选择要填报的项目 / 草稿",
                true,
                "1001",
                "conv-req-fast"));

        HitlCardPayload fastPending = waitForPending("conv-req-fast");
        assertEquals(TYPE_QUESTION, fastPending.type());
        assertFalse(fastPending.allowSessionGrant());
        assertTrue(fastPending.remainingSeconds() > 600); // 默认 30 分钟有效期
        Map<String, Object> raw = (Map<String, Object>) fastPending.rawPayload();
        assertEquals(Boolean.TRUE, raw.get("required"));
        List<Map<String, Object>> qList = (List<Map<String, Object>>) raw.get("questions");
        assertEquals("草稿批次 DOC-202611 · 已填 2/8 项",
                ((Map<String, Object>) qList.get(0).get("optionDescriptions")).get("1012测试 / 前期配套文件"));

        DecideResponse fastResp = decisionService.decide(
                fastPending.decisionId(), 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_SUBMIT_ANSWERS, fastPending.revision(), "cmd-req-1", null,
                        Map.of("Q1", "1012测试 / 前期配套文件")));
        assertEquals(MODE_INLINE_RESUMED, fastResp.mode());
        assertTrue(fastFuture.get().contains("1012测试 / 前期配套文件"));

        // 2. required=true 超时转 HitlParkException 挂起，不软跳过，且慢路径恢复前置短路不二次弹窗
        props.setInlineAwaitSeconds(1);
        List<AskUserQuestionTool.QuestionItem> reqQuestions = List.of(
                new AskUserQuestionTool.QuestionItem("Q1", "请选择审核结论：", "single", List.of("审批通过", "驳回退回"), false));
        HitlParkException parkEx = assertThrows(HitlParkException.class, () ->
                askTool.askUserQuestion(reqQuestions, "请确认审核处理方式", true, "1001", "conv-req-park"));
        String decId = parkEx.getDecisionId();
        assertNotNull(decId);

        List<HitlCardPayload> stillPending = decisionService.listPending(1001L, "conv-req-park");
        assertEquals(1, stillPending.size());
        assertEquals(STATUS_PENDING, stillPending.get(0).status());

        DecideResponse slowResp = decisionService.decide(
                decId, 1001L, "张三", "127.0.0.1",
                new DecideRequest(OPTION_SUBMIT_ANSWERS, stillPending.get(0).revision(), "cmd-req-2", null,
                        Map.of("Q1", "驳回退回")));
        assertEquals(MODE_NEED_RESUME, slowResp.mode());

        RunnableParams rp = RunnableParams.builder().conversationId("conv-req-park").userId("1001").build();
        ToolExecutionContext.set(rp, "conv-req-park", "tc-req", null, null, decId, null, null);
        try {
            String resumedOut = askTool.askUserQuestion(reqQuestions, "请确认审核处理方式", true, "1001", "conv-req-park");
            assertTrue(resumedOut.contains("驳回退回"));
            assertEquals(1, decisionService.listByConversation(1001L, "conv-req-park").size());
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private HitlCardPayload waitForPending(String convId) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<HitlCardPayload> list = decisionService.listPending(1001L, convId);
            if (!list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(50);
        }
        fail("等待待处理决策单超时: " + convId);
        return null;
    }
}

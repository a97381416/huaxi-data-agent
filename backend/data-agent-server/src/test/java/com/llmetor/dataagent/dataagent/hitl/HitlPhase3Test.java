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
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

        // ConnectException -> 未发送，不抛 UnknownOutcomeException
        assertFalse(RestActionClient.isUnknownOutcomeCause(new ConnectException("Connection refused")));
        // SocketTimeoutException(Read timed out) -> 已发送结果未知，抛 UnknownOutcomeException
        assertTrue(RestActionClient.isUnknownOutcomeCause(new SocketTimeoutException("Read timed out")));
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

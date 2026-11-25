package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.memory.AgentxSessionStore;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-SA1 内核测试：状态隔离、跨线程凭证继承、流透传+终态唯一、同轮串行锁、硬超时熔断。
 */
class SubAgentIsolationTest {

    static class ProbeTools {
        final AtomicReference<String> seenUserId = new AtomicReference<>();
        final AtomicReference<String> seenConvId = new AtomicReference<>();
        final AtomicReference<String> seenToken = new AtomicReference<>();

        @Tool(description = "回显入参并校验注入凭证")
        public String whoami(String userId, String conversationId, String token) {
            seenUserId.set(userId);
            seenConvId.set(conversationId);
            seenToken.set(token);
            return "{\"userId\":\"" + userId + "\",\"convId\":\"" + conversationId
                    + "\",\"tokenPresent\":" + (token != null && !token.isBlank()) + "}";
        }

        @Tool(description = "模拟慢工具")
        public String slow() {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            return "done";
        }
    }

    @Test
    void subAgentInheritsCredentialsAndStreamsEventsWithSingleComplete() {
        AgentTaskManager sharedTaskMgr = new AgentTaskManager();
        ProbeTools probe = new ProbeTools();

        // 子 Agent 脚本：第 1 轮故意传假 userId=999999，第 2 轮返回最终结论
        ScriptedChatModel subLlm = new ScriptedChatModel();
        subLlm.enqueue(Flux.just(
                ScriptedChatModel.toolCallChunk("sub_tc_1", "whoami",
                        "{\"userId\":\"999999\",\"conversationId\":\"fake\",\"token\":\"\"}")));
        subLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("子助手结论: OK")));

        ReactAgent subAgent = ReactAgent.builder()
                .chatModel(subLlm)
                .tools(Arrays.asList(ToolCallbacks.from(probe)))
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        ToolCallback subTool = new SubAgentTool("call_demo_sub", "demo-sub", "演示子助手", () -> subAgent);

        // 主 Agent 脚本：第 1 轮调 call_demo_sub，第 2 轮输出总结
        ScriptedChatModel routerLlm = new ScriptedChatModel();
        routerLlm.enqueue(Flux.just(
                ScriptedChatModel.toolCallChunk("parent_tc_1", "call_demo_sub", "{\"task\":\"检查身份\"}")));
        routerLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("主助手总结完成")));

        ReactAgent router = ReactAgent.builder()
                .chatModel(routerLlm)
                .tools(List.of(subTool))
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        RunnableParams params = RunnableParams.builder()
                .conversationId("root_conv_1")
                .userId("real_user_42")
                .toolParams(Map.of("userId", "real_user_42", "token", "secret_jwt"))
                .build();

        List<AgentStreamEvent> events = router.stream("请检查", params)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(events);
        // 1. 凭证强制继承防伪造 + conversationId 强绑为 rootConvId（不含 ::demo-sub）
        assertEquals("real_user_42", probe.seenUserId.get());
        assertEquals("root_conv_1", probe.seenConvId.get());
        assertEquals("secret_jwt", probe.seenToken.get());

        // 2. 子事件带 agentCode + parentToolCallId 实时透传
        boolean hasSubToolStart = events.stream().anyMatch(e ->
                e.getType() == AgentStreamEvent.Type.TOOL_START
                        && "whoami".equals(e.getToolName())
                        && "demo-sub".equals(e.getAgentCode())
                        && "parent_tc_1".equals(e.getParentToolCallId()));
        assertTrue(hasSubToolStart, "应收到带归属标记的子 TOOL_START 事件");

        // 3. 终态唯一：全流只能有 1 次 COMPLETE，子 COMPLETE 不得外泄
        long completeCount = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.COMPLETE || e.getType() == AgentStreamEvent.Type.PAUSED)
                .count();
        assertEquals(1L, completeCount, "整条流终态事件必须严格为 1");
    }

    @Test
    void sameRoundDualSubAgentCallsRunSeriallyWithoutLockConflict() {
        AgentTaskManager sharedTaskMgr = new AgentTaskManager();
        ScriptedChatModel subLlm = new ScriptedChatModel();
        subLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("第一次子回复")));
        subLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("第二次子回复")));

        ReactAgent subAgent = ReactAgent.builder()
                .chatModel(subLlm)
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        ToolCallback subTool = new SubAgentTool("call_demo_sub", "demo-sub", "演示子助手", () -> subAgent);

        ScriptedChatModel routerLlm = new ScriptedChatModel();
        routerLlm.enqueue(Flux.just(
                ScriptedChatModel.toolCallChunk("p1", "call_demo_sub", "{\"task\":\"任务1\"}"),
                ScriptedChatModel.toolCallChunk("p2", "call_demo_sub", "{\"task\":\"任务2\"}")));
        routerLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("双任务合并完成")));

        ReactAgent router = ReactAgent.builder()
                .chatModel(routerLlm)
                .tools(List.of(subTool))
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        List<AgentStreamEvent> events = router.stream("做两件事", RunnableParams.of("root_serial_1"))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(events);
        long subToolEnds = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.TOOL_END
                        && "call_demo_sub".equals(e.getToolName())
                        && Boolean.TRUE.equals(e.getSuccess()))
                .count();
        assertEquals(2L, subToolEnds, "同轮两次子调用均应串行成功无锁冲突");
    }

    @Test
    void subAgentTimeoutStopsChildAndLetsParentComplete() {
        AgentTaskManager sharedTaskMgr = new AgentTaskManager();
        ProbeTools probe = new ProbeTools();

        ScriptedChatModel subLlm = new ScriptedChatModel();
        subLlm.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("slow_1", "slow", "{}")));

        ReactAgent subAgent = ReactAgent.builder()
                .chatModel(subLlm)
                .tools(Arrays.asList(ToolCallbacks.from(probe)))
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        // 设为 300ms 硬超时
        ToolCallback subTool = new SubAgentTool("call_demo_sub", "demo-sub", "慢子助手", () -> subAgent, 300L);

        ScriptedChatModel routerLlm = new ScriptedChatModel();
        routerLlm.enqueue(Flux.just(
                ScriptedChatModel.toolCallChunk("p_timeout", "call_demo_sub", "{\"task\":\"跑慢工具\"}")));
        routerLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("子任务超时已兜底")));

        ReactAgent router = ReactAgent.builder()
                .chatModel(routerLlm)
                .tools(List.of(subTool))
                .taskManager(sharedTaskMgr)
                .enableCompaction(false)
                .build();

        List<AgentStreamEvent> events = router.stream("跑慢的", RunnableParams.of("root_timeout_1"))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(events);
        boolean timeoutReturned = events.stream().anyMatch(e ->
                e.getType() == AgentStreamEvent.Type.TOOL_END
                        && "call_demo_sub".equals(e.getToolName())
                        && e.getResult() != null && e.getResult().contains("超时"));
        assertTrue(timeoutReturned, "超时应返回结构化超时提示");
        assertFalse(sharedTaskMgr.hasRunningTask("root_timeout_1::demo-sub"), "超时后子任务必须已注销无残留");
    }

    @Test
    void subAgentTimeoutStopsZombieRoundsAndDoesNotPolluteHistory() throws InterruptedException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sub_zombie_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        AgentxSessionStore sessionStore = new AgentxSessionStore(ds);
        AgentTaskManager sharedTaskMgr = new AgentTaskManager();

        AtomicInteger zombieCalls = new AtomicInteger(0);
        ProbeTools probe = new ProbeTools() {
            @Override
            @Tool(description = "模拟慢工具（忽略中断睡满 700ms）")
            public String slow() {
                long end = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < end) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        // 即使工具自身吞掉中断，执行器也必须在回调处拦截，严禁进入下一轮
                    }
                }
                return "slow_finished";
            }
        };

        // 第 1 次调用：调 slow 后若僵尸续跑会触发第 2 轮；提前准备正常响应检验是否被误消费
        ScriptedChatModel subLlm = new ScriptedChatModel();
        subLlm.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("slow_1", "slow", "{}")));
        subLlm.enqueue(Flux.defer(() -> {
            zombieCalls.incrementAndGet();
            return Flux.just(ScriptedChatModel.textChunk("绝不应该执行的僵尸回复"));
        }));

        ToolCallback subTool = new SubAgentTool("call_demo_sub", "demo-sub", "慢子助手",
                () -> ReactAgent.builder()
                        .chatModel(subLlm)
                        .tools(Arrays.asList(ToolCallbacks.from(probe)))
                        .taskManager(sharedTaskMgr)
                        .sessionStore(sessionStore)
                        .enableCompaction(false)
                        .build(),
                200L);

        ScriptedChatModel routerLlm = new ScriptedChatModel();
        routerLlm.enqueue(Flux.just(
                ScriptedChatModel.toolCallChunk("p_timeout", "call_demo_sub", "{\"task\":\"跑慢工具\"}")));
        routerLlm.enqueue(Flux.just(ScriptedChatModel.textChunk("超时兜底完成")));

        ReactAgent router = ReactAgent.builder()
                .chatModel(routerLlm)
                .tools(List.of(subTool))
                .taskManager(sharedTaskMgr)
                .sessionStore(sessionStore)
                .enableCompaction(false)
                .build();

        List<AgentStreamEvent> events = router.stream("跑慢的", RunnableParams.of("root_zombie_1"))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertNotNull(events);
        // 等待原本 700ms 的慢工具彻底跑完
        Thread.sleep(800);

        assertEquals(0, zombieCalls.get(), "超时强停后绝不允许出现僵尸续跑轮次");
        assertTrue(sessionStore.loadHistory("root_zombie_1::demo-sub").isEmpty(),
                "超时强停的半截失败轨迹不得写入 agentx_session 污染后续上下文");
    }
}

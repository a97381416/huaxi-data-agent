package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.model.AgentResult;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 主循环全场景（对应测试用例 M1-A/C/E 组），mock 模型 + 真实工具回调。
 */
class AgentLoopTest {

    // ==================== 演示工具（echo 成功 / boom 必失败） ====================

    static class DemoTools {
        @Tool(description = "原样返回输入文本，用于测试")
        public String echo(String text) {
            return "echo: " + text;
        }

        @Tool(description = "必然失败的工具，用于测试错误回灌")
        public String boom() {
            throw new RuntimeException("预期内的失败");
        }
    }

    private static List<ToolCallback> demoTools() {
        return Arrays.asList(ToolCallbacks.from(new DemoTools()));
    }

    private static ReactAgent agent(ScriptedChatModel model) {
        return ReactAgent.builder()
                .chatModel(model)
                .instructions("你是测试助手")
                .tools(demoTools())
                .thinkingMode(ThinkingMode.THINK_TAG)
                .retryIntervalMs(50)
                .build();
    }

    private static List<AgentStreamEvent> run(ReactAgent agent, String convId, String query) {
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        agent.stream(query, RunnableParams.of(convId))
                .doOnNext(events::add)
                .blockLast();
        return events;
    }

    private static List<AgentStreamEvent.Type> types(List<AgentStreamEvent> events) {
        return events.stream().map(AgentStreamEvent::getType).toList();
    }

    // ==================== A01 事件顺序 ====================

    @Test
    void eventOrderThinkingTextToolComplete() {
        // M1-A01：thinking→text→tool_start→tool_end→complete（含跨 chunk 思考）
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.textChunk("<think>需"),
                ScriptedChatModel.textChunk("要回声</think>调用工具"),
                ScriptedChatModel.toolCallChunk("c1", "echo", "{\"text\":\"hello\"}")));
        model.enqueue(FluxJust.of(
                ScriptedChatModel.textChunk("<think>拿到结果"),
                ScriptedChatModel.textChunk("了</think>最终"),
                ScriptedChatModel.textChunk("答案：echo: hello")));

        List<AgentStreamEvent> events = run(agent(model), "conv_order", "请回声 hello");

        // 事件按 chunk 粒度逐个发出：thinking 两个 chunk→两次 THINKING，text 同理
        assertEquals(List.of(
                AgentStreamEvent.Type.THINKING, AgentStreamEvent.Type.THINKING,
                AgentStreamEvent.Type.TEXT,
                AgentStreamEvent.Type.TOOL_START, AgentStreamEvent.Type.TOOL_END,
                AgentStreamEvent.Type.THINKING, AgentStreamEvent.Type.THINKING,
                AgentStreamEvent.Type.TEXT, AgentStreamEvent.Type.TEXT,
                AgentStreamEvent.Type.COMPLETE), types(events));
        AgentStreamEvent complete = events.get(events.size() - 1);
        assertEquals("conv_order", complete.getConversationId());
        assertTrue(complete.getSessionId() != null && !complete.getSessionId().isEmpty());
    }

    // ==================== A02 失败工具错误 JSON 回灌、循环继续 ====================

    @Test
    void failedToolErrorJsonFedBackAndLoopContinues() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(ScriptedChatModel.toolCallChunk("c1", "boom", "{}")));
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("工具失败了，但我继续回答")));

        List<AgentStreamEvent> events = run(agent(model), "conv_boom", "测试失败工具");

        List<AgentStreamEvent.Type> t = types(events);
        assertTrue(t.contains(AgentStreamEvent.Type.TOOL_START), "应有 TOOL_START: " + t);
        assertFalse(t.contains(AgentStreamEvent.Type.TOOL_END), "失败不发 TOOL_END: " + t);
        assertTrue(t.contains(AgentStreamEvent.Type.COMPLETE), "循环未终止: " + t);

        // 第二轮请求里应包含错误 JSON 回灌
        assertEquals(2, model.capturedPrompts.size());
        List<org.springframework.ai.chat.messages.Message> round2 =
                model.capturedPrompts.get(1).getInstructions();
        boolean found = round2.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(trm -> trm.getResponses().stream())
                .anyMatch(r -> r.responseData().contains("boom") && r.responseData().contains("error"));
        assertTrue(found, "错误 JSON 应回灌给模型: " + round2);
    }

    // ==================== A03 toolCall 分片合并 ====================

    @Test
    void toolCallArgumentsFragmentsMerged() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.toolCallChunk("c1", "echo", "{\"te"),
                ScriptedChatModel.toolCallChunk("c1", null, "xt\":\"frag"),
                ScriptedChatModel.toolCallChunk("c1", null, "ment\"}")));
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("完成")));

        List<AgentStreamEvent> events = run(agent(model), "conv_frag", "回声 fragment");

        long startCount = events.stream().filter(e -> e.getType() == AgentStreamEvent.Type.TOOL_START).count();
        assertEquals(1, startCount, "分片只执行一次");
        AgentStreamEvent start = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.TOOL_START).findFirst().orElseThrow();
        assertTrue(start.getArguments().contains("fragment"), "arguments 应合并完整: " + start.getArguments());
        // 工具真实拿到完整参数（echo 输出含 fragment；Spring AI 对 String 返回做 JSON 编码）
        AgentStreamEvent end = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.TOOL_END).findFirst().orElseThrow();
        assertTrue(end.getResult().contains("echo: fragment"),
                "结果应含完整参数回显: " + end.getResult());
    }

    // ==================== A05 maxRounds forceFinal ====================

    @Test
    void maxRoundsForceFinal() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(ScriptedChatModel.toolCallChunk("c1", "echo", "{\"text\":\"x\"}")));
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("被逼出来的最终答案")));

        ReactAgent a = ReactAgent.builder()
                .chatModel(model)
                .instructions("t")
                .tools(demoTools())
                .thinkingMode(ThinkingMode.DISABLED)
                .maxRounds(1)
                .build();
        AgentResult result = a.call("问", RunnableParams.of("conv_force"));

        assertEquals(AgentResult.Status.COMPLETED, result.getStatus());
        assertEquals("被逼出来的最终答案", result.getAnswer());
        // 第二次调用不带工具（forceFinal）
        assertEquals(2, model.capturedPrompts.size());
        assertTrue(model.capturedPrompts.get(1).getOptions()
                        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco
                        && tco.getToolCallbacks().isEmpty(),
                "forceFinal 轮不应带工具");
        // 占位响应已补（assistant 的 toolCall 有配对响应）
        List<org.springframework.ai.chat.messages.Message> round2 =
                model.capturedPrompts.get(1).getInstructions();
        assertTrue(round2.stream().anyMatch(m -> m instanceof ToolResponseMessage), "应有占位工具响应");
    }

    // ==================== A07 纯文本直答 ====================

    @Test
    void pureTextNoTool() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.textChunk("<think>简单题</think>"),
                ScriptedChatModel.textChunk("答案是 2")));

        List<AgentStreamEvent> events = run(agent(model), "conv_pure", "1+1=?");

        assertEquals(List.of(AgentStreamEvent.Type.THINKING, AgentStreamEvent.Type.TEXT,
                AgentStreamEvent.Type.COMPLETE), types(events));
        assertEquals(1, model.capturedPrompts.size(), "无工具则只有一轮");
    }

    // ==================== C01 中途打断收 Paused ====================

    @Test
    void interruptDuringStreamingYieldsPaused() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        // 慢速流：先吐思考，然后长时间不结束（模拟长回答）
        model.enqueue(reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Flux.just(ScriptedChatModel.textChunk("<think>长思考开始"))
                        .delayElements(java.time.Duration.ofMillis(100)),
                reactor.core.publisher.Flux.never()));

        ReactAgent a = agent(model);
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch paused = new CountDownLatch(1);
        a.stream("慢问题", RunnableParams.of("conv_pause"))
                .doOnNext(e -> {
                    events.add(e);
                    if (e.getType() == AgentStreamEvent.Type.PAUSED) {
                        paused.countDown();
                    }
                })
                .subscribe();

        // 等首事件到达后打断
        await().atMost(5, TimeUnit.SECONDS).until(() -> !events.isEmpty());
        boolean interrupted = a.interrupt("conv_pause", "用户叫停");
        assertTrue(interrupted, "应找到运行任务");
        assertTrue(paused.await(5, TimeUnit.SECONDS), "应收到 Paused 事件");

        List<AgentStreamEvent.Type> t = types(events);
        assertEquals(AgentStreamEvent.Type.PAUSED, t.get(t.size() - 1), "Paused 收尾: " + t);
        assertFalse(t.contains(AgentStreamEvent.Type.COMPLETE), "Paused 流不发 Complete 事件");
        assertTrue(a.hasInterruptedState("conv_pause"), "快照已保存(内存)");
        com.llmetor.miniagentx.model.PauseState snapshot = a.getInterruptedState("conv_pause");
        assertTrue(snapshot != null && snapshot.getCurrentRound() >= 1, "快照含轮次信息");
        assertFalse(a.hasRunningTask("conv_pause"), "任务已注销");
    }

    // ==================== E02 结构化输出修复 ====================

    @Test
    void structuredOutputRepairFencedJson() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.textChunk("结果是：```json\n{\"answer\":\"4"),
                ScriptedChatModel.textChunk("2\",\"tags\":[\"a\",]}"),
                ScriptedChatModel.textChunk("\n```")));

        ReactAgent a = agent(model);
        AgentResult result = a.call("给我结构化结果",
                RunnableParams.builder().conversationId("conv_struct")
                        .outputType(SampleOutput.class).build());

        assertEquals(AgentResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getAnswer().startsWith("{\"answer\":\"42\""),
                "围栏剥离+尾逗号修复: " + result.getAnswer());
    }

    /** 结构化输出目标类型 */
    static class SampleOutput {
        private String answer;
        private List<String> tags;

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    // ==================== A04 并发工具按序回灌 ====================

    @Test
    void concurrentToolsInOrderFeedback() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.toolCallChunk("c1", "echo", "{\"text\":\"first\"}"),
                ScriptedChatModel.toolCallChunk("c2", "echo", "{\"text\":\"second\"}"),
                ScriptedChatModel.toolCallChunk("c3", "echo", "{\"text\":\"third\"}")));
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("done")));

        List<AgentStreamEvent> events = run(agent(model), "conv_conc", "并发三连");

        List<String> endOrder = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.TOOL_END)
                .map(AgentStreamEvent::getToolCallId)
                .toList();
        assertEquals(List.of("c1", "c2", "c3"), endOrder, "ToolEnd 按原始顺序统一发");

        // 回灌消息同样按序
        List<org.springframework.ai.chat.messages.Message> round2 =
                model.capturedPrompts.get(1).getInstructions();
        List<String> responseOrder = round2.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage) m).getResponses())
                .flatMap(List::stream)
                .map(ToolResponseMessage.ToolResponse::id)
                .toList();
        assertEquals(List.of("c1", "c2", "c3"), responseOrder);
    }

    // ==================== 未知工具不终止 ====================

    @Test
    void unknownToolReturnsErrorJsonAndContinues() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(ScriptedChatModel.toolCallChunk("c1", "no_such_tool", "{}")));
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("没这个工具，我直接回答")));

        List<AgentStreamEvent> events = run(agent(model), "conv_unknown", "调用不存在的工具");

        assertTrue(types(events).contains(AgentStreamEvent.Type.COMPLETE), "不终止");
        List<org.springframework.ai.chat.messages.Message> round2 =
                model.capturedPrompts.get(1).getInstructions();
        boolean found = round2.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(trm -> trm.getResponses().stream())
                .anyMatch(r -> r.responseData().contains("不存在"));
        assertTrue(found, "未知工具错误 JSON 回灌: " + round2);
    }

    // ==================== 自定义参数进 system prompt ====================

    @Test
    void customParamsInjectedIntoSystemPrompt() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(ScriptedChatModel.textChunk("ok")));

        ReactAgent a = agent(model);
        a.call("问", RunnableParams.builder().conversationId("conv_sys")
                .customParams(java.util.Map.of("env", "test")).build());

        org.springframework.ai.chat.messages.Message sys =
                model.capturedPrompts.get(0).getInstructions().get(0);
        assertTrue(sys instanceof org.springframework.ai.chat.messages.SystemMessage);
        assertTrue(sys.getText().contains("## 系统参数"), "customParams 拼进 system: " + sys.getText());
        assertTrue(sys.getText().contains("env: test"));
    }

    // ==================== REASONING_CONTENT 模式（MiniMax M3.1-Flash 路线） ====================

    @Test
    void reasoningContentModeEmitsThinkingFromReasoningField() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(FluxJust.of(
                ScriptedChatModel.reasoningChunk("先想：1+1", ""),
                ScriptedChatModel.reasoningChunk("等于 2", ""),
                ScriptedChatModel.textChunk("答案是 2")));

        ReactAgent a = ReactAgent.builder()
                .chatModel(model)
                .instructions("t")
                .tools(demoTools())
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .build();

        List<AgentStreamEvent> events = run(a, "conv_reasoning", "1+1=?");

        assertEquals(List.of(
                AgentStreamEvent.Type.THINKING, AgentStreamEvent.Type.THINKING,
                AgentStreamEvent.Type.TEXT,
                AgentStreamEvent.Type.COMPLETE), types(events));

        String thinking = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.THINKING)
                .map(AgentStreamEvent::getContent)
                .reduce("", String::concat);
        assertEquals("先想：1+1等于 2", thinking, "思考内容来自 reasoning_content 字段");

        String text = events.stream()
                .filter(e -> e.getType() == AgentStreamEvent.Type.TEXT)
                .map(AgentStreamEvent::getContent)
                .reduce("", String::concat);
        assertEquals("答案是 2", text, "正文原样透传");
    }

    // ==================== 便捷构造 ====================

    static final class FluxJust {
        static <T> reactor.core.publisher.Flux<T> of(@SuppressWarnings("unchecked") T... items) {
            return reactor.core.publisher.Flux.fromArray(items);
        }
    }
}

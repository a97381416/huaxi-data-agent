package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.interrupt.InMemoryPauseStateStore;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 中断恢复全场景（对应测试用例 M3-B 组）：打断→快照→resume 三语义。
 * mock 模型 + 真实工具回调 + InMemory 快照存储；JDBC 版序列化往返见 interrupt 包单测。
 */
class ResumeLoopTest {

    static class DemoTools {
        @Tool(description = "原样返回输入文本，用于测试")
        public String echo(String text) {
            return "echo: " + text;
        }
    }

    private static List<ToolCallback> demoTools() {
        return List.of(ToolCallbacks.from(new DemoTools()));
    }

    private static ReactAgent agent(ScriptedChatModel model) {
        return ReactAgent.builder()
                .chatModel(model)
                .instructions("你是测试助手")
                .tools(demoTools())
                .thinkingMode(ThinkingMode.DISABLED)
                .retryIntervalMs(50)
                .stateStore(new InMemoryPauseStateStore())
                .build();
    }

    private static List<AgentStreamEvent.Type> types(List<AgentStreamEvent> events) {
        return events.stream().map(AgentStreamEvent::getType).toList();
    }

    /**
     * 打断一个「LLM 流已吐出 toolCall 但尚未执行」的执行，返回快照。
     * 覆盖 M1 未覆盖的分支：LLM_STREAMING 中断也登记 pendingToolCalls（M3 恢复重放的输入）。
     */
    private static PauseState interruptWithPendingTool(ReactAgent a, ScriptedChatModel model,
                                                       String convId) throws Exception {
        model.enqueue(Flux.concat(
                Flux.just(ScriptedChatModel.toolCallChunk("c1", "echo", "{\"text\":\"hi\"}"))
                        .delayElements(java.time.Duration.ofMillis(80)),
                Flux.never()));
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        a.stream("做个回声", RunnableParams.of(convId))
                .doOnNext(events::add)
                .subscribe();
        // toolCall chunk 在流式阶段不产生事件：等模型收到请求、chunk 被消费后（80ms 吐出）再打断
        await().atMost(5, TimeUnit.SECONDS).until(() -> !model.capturedPrompts.isEmpty());
        Thread.sleep(400);
        assertTrue(a.interrupt(convId, "用户叫停"), "应找到运行任务");
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> types(events).contains(AgentStreamEvent.Type.PAUSED));
        assertEquals(AgentStreamEvent.Type.PAUSED,
                types(events).get(types(events).size() - 1), "Paused 收尾");
        assertTrue(a.hasInterruptedState(convId), "快照已保存");
        PauseState snapshot = a.getInterruptedState(convId);
        assertEquals(1, snapshot.getPendingToolCalls().size(),
                "LLM 流中断也应登记 pending toolCall");
        return snapshot;
    }

    // ==================== B02 空消息恢复：本地工具重放 ====================

    @Test
    void resumeWithEmptyQueryReplaysPendingToolLocally() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        ReactAgent a = agent(model);
        String conv = "conv_resume_replay";
        interruptWithPendingTool(a, model, conv);

        // 恢复后的模型脚本：基于重放结果给最终答案
        model.enqueue(Flux.just(ScriptedChatModel.textChunk("恢复后的最终答案")));

        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        a.resumeStream(conv, null).doOnNext(events::add).blockLast();

        List<AgentStreamEvent.Type> t = types(events);
        assertTrue(t.contains(AgentStreamEvent.Type.TOOL_START), "重放应发 TOOL_START: " + t);
        assertTrue(t.contains(AgentStreamEvent.Type.TOOL_END), "重放成功应发 TOOL_END: " + t);
        assertEquals(AgentStreamEvent.Type.COMPLETE, t.get(t.size() - 1), "恢复流最终 Complete: " + t);
        // 重放是本地执行：capturedPrompts = 打断前 1 次 + 恢复后 1 次（不重调 LLM 完成重放）
        assertEquals(2, model.capturedPrompts.size(), "重放不应产生额外 LLM 调用");
        // 恢复轮的 prompt 里应包含重放的真实工具结果、无占位残留
        List<Message> resumeRound = model.capturedPrompts.get(model.capturedPrompts.size() - 1).getInstructions();
        assertTrue(hasToolResponse(resumeRound, "echo: hi"), "重放结果应回灌: " + resumeRound);
        assertFalse(hasToolResponse(resumeRound, "[占位]"), "占位响应应已被剥离: " + resumeRound);
        // 非暂停终态：快照已删除
        assertFalse(a.hasInterruptedState(conv), "完成后快照应删除");
    }

    // ==================== B03 带指令恢复：不重放，模型自决策 ====================

    @Test
    void resumeWithQuerySkipsToolReplayAndInjectsClarification() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        ReactAgent a = agent(model);
        String conv = "conv_resume_query";
        interruptWithPendingTool(a, model, conv);

        model.enqueue(Flux.just(ScriptedChatModel.textChunk("按新指令给出的答案")));

        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        a.resumeStream(conv, "继续，改成按月统计").doOnNext(events::add).blockLast();

        assertEquals(AgentStreamEvent.Type.COMPLETE,
                types(events).get(types(events).size() - 1));
        assertFalse(types(events).contains(AgentStreamEvent.Type.TOOL_START), "带指令恢复不重放工具");
        List<Message> resumeRound = model.capturedPrompts.get(model.capturedPrompts.size() - 1).getInstructions();
        // 跳过占位 + 澄清语注入
        assertTrue(hasToolResponse(resumeRound, "工具执行被用户中断，用户返回后给出了新的指示"),
                "应注入跳过占位: " + resumeRound);
        UserMessage lastUser = null;
        for (Message m : resumeRound) {
            if (m instanceof UserMessage um) {
                lastUser = um;
            }
        }
        assertTrue(lastUser != null && lastUser.getText().contains("[用户中断后返回]")
                        && lastUser.getText().contains("继续，改成按月统计"),
                "澄清语应包装原始任务与新消息: " + (lastUser == null ? "null" : lastUser.getText()));
        assertFalse(a.hasInterruptedState(conv), "完成后快照应删除");
    }

    // ==================== 纯文本中断后恢复（无 pending toolCall） ====================

    @Test
    void resumePureTextInterruptContinuesRounds() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        ReactAgent a = agent(model);
        String conv = "conv_resume_text";
        model.enqueue(Flux.concat(
                Flux.just(ScriptedChatModel.textChunk("半截正文"))
                        .delayElements(java.time.Duration.ofMillis(80)),
                Flux.never()));
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        a.stream("写点什么", RunnableParams.of(conv)).doOnNext(events::add).subscribe();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !model.capturedPrompts.isEmpty());
        Thread.sleep(400);
        assertTrue(a.interrupt(conv, "叫停"));
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> types(events).contains(AgentStreamEvent.Type.PAUSED));
        assertTrue(a.getInterruptedState(conv).getPendingToolCalls().isEmpty(),
                "纯文本中断无 pending");

        model.enqueue(Flux.just(ScriptedChatModel.textChunk("，接上后的完整结尾")));
        List<AgentStreamEvent> resumeEvents = new CopyOnWriteArrayList<>();
        a.resumeStream(conv, null).doOnNext(resumeEvents::add).blockLast();
        assertEquals(AgentStreamEvent.Type.COMPLETE,
                types(resumeEvents).get(types(resumeEvents).size() - 1));
        assertFalse(a.hasInterruptedState(conv));
    }

    // ==================== 无快照恢复报错 ====================

    @Test
    void resumeWithoutSnapshotErrors() {
        ScriptedChatModel model = new ScriptedChatModel();
        ReactAgent a = agent(model);
        assertNull(a.getInterruptedState("conv_none"));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> a.resumeStream("conv_none", null).blockLast());
    }

    // ==================== 工具方法 ====================

    private static boolean hasToolResponse(List<Message> messages, String expect) {
        return messages.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(trm -> trm.getResponses().stream())
                .anyMatch(r -> r.responseData() != null && r.responseData().contains(expect));
    }
}

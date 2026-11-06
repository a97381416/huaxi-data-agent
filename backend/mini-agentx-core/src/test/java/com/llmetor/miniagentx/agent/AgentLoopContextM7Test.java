package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.context.ContextPolicy;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import com.llmetor.miniagentx.tools.search.DeferredToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 主循环接线测试：tool_search 延迟发现"第 N 轮发现 → 第 N+1 轮可调用" +
 * 每轮 LLM 调用前的上下文压缩内联生效。
 */
class AgentLoopContextM7Test {

    static class EchoTools {
        @Tool(name = "echo", description = "原样返回输入文本，回声/复读测试用")
        public String echo(String text) {
            return "echo: " + text;
        }

        @Tool(name = "echoBig", description = "把输入重复 300 次返回（构造大结果用）")
        public String echoBig(String text) {
            return text.repeat(300);
        }
    }

    private static List<String> promptToolNames(Prompt prompt) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        assertNotNull(options, "每轮都应带 ToolCallingChatOptions");
        return options.getToolCallbacks().stream()
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    private static List<AgentStreamEvent> run(ReactAgent agent, String convId, String query) {
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        agent.stream(query, RunnableParams.of(convId))
                .doOnNext(events::add)
                .blockLast();
        return events;
    }

    // ==================== A 组：延迟发现 ====================

    @Test
    void deferredToolDiscoveredInRoundNIsCallableInRoundNPlusOne() {
        DeferredToolRegistry registry = new DeferredToolRegistry();
        registry.registerAll(Arrays.asList(ToolCallbacks.from(new EchoTools()))); // echo/echoBig 全在延迟层

        ScriptedChatModel model = new ScriptedChatModel();
        // 第 1 轮：调 tool_search 发现 echo
        model.enqueue(Flux.just(ScriptedChatModel.toolCallChunk(
                "c1", "tool_search", "{\"query\":\"回声 原样返回 文本\"}")));
        // 第 2 轮：echo 已可调用
        model.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("c2", "echo", "{\"text\":\"hi\"}")));
        // 第 3 轮：收尾
        model.enqueue(Flux.just(ScriptedChatModel.textChunk("done")));

        ReactAgent agent = ReactAgent.builder()
                .chatModel(model)
                .instructions("测试助手：需要工具先 tool_search 发现")
                .tools(List.of()) // 常驻层为空——首轮只剩 tool_search
                .deferredTools(registry)
                .thinkingMode(ThinkingMode.DISABLED)
                .retryIntervalMs(50)
                .build();

        List<AgentStreamEvent> events = run(agent, "conv_defer", "请回声 hi");
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentStreamEvent.Type.COMPLETE));

        assertEquals(3, model.capturedPrompts.size());
        // 第 1 轮：只有 tool_search + context_reload（压缩默认开启，取回工具常驻），延迟工具不在初始清单
        List<String> round1 = promptToolNames(model.capturedPrompts.get(0));
        assertTrue(round1.contains("tool_search") && !round1.contains("echo"),
                "首轮应极简（常驻+tool_search+context_reload，无延迟工具）: " + round1);
        // 第 2 轮：发现后的 echo 进入清单（每轮重建生效）
        List<String> round2 = promptToolNames(model.capturedPrompts.get(1));
        assertTrue(round2.contains("echo"), "发现后下一轮应可调用: " + round2);
        assertTrue(round2.contains("tool_search"), "tool_search 常驻每轮: " + round2);
        // 第 3 轮 echo 仍可用（请求内跨轮累积）
        assertTrue(promptToolNames(model.capturedPrompts.get(2)).contains("echo"));
    }

    // ==================== B 组：压缩内联 ====================

    @Test
    void compactionRunsBeforeRoundInputAndRewritesLargeHistoryMessage() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("c1", "echoBig", "{\"text\":\"大结果数据X\"}")));
        model.enqueue(Flux.just(ScriptedChatModel.toolCallChunk("c2", "echoBig", "{\"text\":\"小\"}")));
        model.enqueue(Flux.just(ScriptedChatModel.textChunk("final")));

        // msgThreshold=5（非系统消息数）/ lastKeep=2 / 大消息 200 token —— 第 3 轮前 tool_big 进历史区被 L2 offload
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(2).msgThreshold(5).tokenThreshold(90_000)
                .minConsecutiveToolMessages(99).minCompressionTokens(10_000)
                .largePayloadTokens(100).offloadPreviewChars(30)
                .maxLlmCompressionCount(3)
                .build();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(model)
                .instructions("测试助手")
                .tools(Arrays.asList(ToolCallbacks.from(new EchoTools())))
                .contextPolicy(policy)
                .thinkingMode(ThinkingMode.DISABLED)
                .retryIntervalMs(50)
                .build();

        List<AgentStreamEvent> events = run(agent, "conv_compact", "构造大结果");
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentStreamEvent.Type.COMPLETE));
        assertEquals(3, model.capturedPrompts.size());

        // 第 2 轮（未触发）：链原样
        assertFalseContainsMarker(model.capturedPrompts.get(1));
        // 第 3 轮（第 2 轮工具结果回灌后消息数达 6）：大工具结果被 offload 替换为预览+uuid
        Prompt round3 = model.capturedPrompts.get(2);
        String round3Json = new com.llmetor.miniagentx.memory.MessageJsonSerializer()
                .toJsonArray(round3.getInstructions());
        assertTrue(round3Json.contains("已压缩 offload"), "第 3 轮输入应含 L2 offload 标记");
        assertTrue(round3Json.contains("uuid="), "标记应含 uuid=");
        // 原文（"大结果数据X"×300）不应再整体进上下文（40 连发只会出现在完整原文里）
        String rawFingerprint = "大结果数据X".repeat(40);
        assertTrue(!round3Json.contains(rawFingerprint), "大结果原文不应整体进入压缩后的输入");
        // 第 2 轮输入里原文还在（那时未触发）
        String round2Json = new com.llmetor.miniagentx.memory.MessageJsonSerializer()
                .toJsonArray(model.capturedPrompts.get(1).getInstructions());
        assertTrue(round2Json.contains(rawFingerprint), "未触发轮应保留原文");
    }

    private static void assertFalseContainsMarker(Prompt prompt) {
        String json = new com.llmetor.miniagentx.memory.MessageJsonSerializer()
                .toJsonArray(prompt.getInstructions());
        assertTrue(!json.contains("已压缩 offload"), "未触发压缩的轮不应有 offload 标记");
    }
}

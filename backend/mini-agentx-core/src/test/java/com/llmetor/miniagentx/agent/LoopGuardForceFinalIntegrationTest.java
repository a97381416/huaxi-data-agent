package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.model.AgentResult;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import com.llmetor.miniagentx.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环熔断集成测试（Fix A）：脚本化模型连续 7 轮纯单工具调用，断言
 * WARN/ESCALATE 提示注入回灌、第 7 轮 BREAK 后走 forceFinal 收口。
 */
class LoopGuardForceFinalIntegrationTest {

    static class EchoTools {
        @Tool(description = "原样返回输入文本，用于测试")
        public String echo(String text) {
            return "echo: " + text;
        }
    }

    @Test
    void sevenPureEchoRoundsBreakAndForceFinal() {
        ScriptedChatModel model = new ScriptedChatModel();
        // 7 轮纯 echo 工具调用（计数 1..7，第 7 次 BREAK）+ 1 轮 forceFinal 文本收口
        for (int i = 0; i < 7; i++) {
            model.enqueue(reactor.core.publisher.Flux.just(
                    ScriptedChatModel.toolCallChunk("c" + i, "echo", "{\"text\":\"x\"}")));
        }
        model.enqueue(reactor.core.publisher.Flux.just(
                ScriptedChatModel.textChunk("被熔断逼出来的最终结论")));

        ReactAgent agent = ReactAgent.builder()
                .chatModel(model)
                .instructions("t")
                .tools(Arrays.asList(ToolCallbacks.from(new EchoTools())))
                .thinkingMode(ThinkingMode.DISABLED)
                .build();   // 默认 LoopGuardConfig.defaults()：3/5/7
        AgentResult result = agent.call("测试熔断", RunnableParams.of("conv_loop_guard"));

        assertEquals(AgentResult.Status.COMPLETED, result.getStatus());
        assertEquals("被熔断逼出来的最终结论", result.getAnswer());

        // 共 8 次 LLM 调用：7 次工具轮 + 1 次 forceFinal（不带工具）
        assertEquals(8, model.capturedPrompts.size(), "调用次数");

        // 第 3 次 echo 后（第 4 个 prompt）注入 WARN
        String round4 = renderInstructions(model.capturedPrompts.get(3));
        assertTrue(round4.contains("[系统提示]"), "第 3 次纯轮后应注入 WARN: " + round4);
        // 第 5 次 echo 后（第 6 个 prompt）注入 ESCALATE
        String round6 = renderInstructions(model.capturedPrompts.get(5));
        assertTrue(round6.contains("[系统警告]"), "第 5 次纯轮后应注入 ESCALATE: " + round6);
        // 第 7 次 echo 被 BREAK：响应被替换为熔断文案，且第 8 轮不带工具（forceFinal）
        String round8 = renderInstructions(model.capturedPrompts.get(7));
        assertTrue(round8.contains("[系统熔断]"), "BREAK 应注入熔断响应: " + round8);
        assertTrue(model.capturedPrompts.get(7).getOptions()
                        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco
                        && tco.getToolCallbacks().isEmpty(),
                "熔断后应进 forceFinal（不带工具）");
    }

    private static String renderInstructions(org.springframework.ai.chat.prompt.Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (org.springframework.ai.chat.messages.Message m : prompt.getInstructions()) {
            sb.append(m.getMessageType()).append(':').append(m.getText() == null ? "" : m.getText()).append('\n');
            if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                for (org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    sb.append(r.name()).append("→").append(r.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}

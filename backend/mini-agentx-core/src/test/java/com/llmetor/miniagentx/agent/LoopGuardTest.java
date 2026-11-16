package com.llmetor.miniagentx.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环熔断器单测（Fix A）：阈值分级 / 纯单工具轮计数 / 穿插不重置 / 豁免名单 / 关闭透传。
 */
class LoopGuardTest {

    private static ToolCall call(String name) {
        return new ToolCall("c", "function", name, "{}");
    }

    private static ToolCall call(String name, String args) {
        return new ToolCall("c", "function", name, args);
    }

    @Test
    void thresholdsWarnEscalateBreak() {
        LoopGuard g = new LoopGuard(LoopGuardConfig.defaults());   // 3/5/7
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite"))).action(), "第 1 次");
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite"))).action(), "第 2 次");
        assertEquals(LoopGuard.Action.WARN, g.evaluate(List.of(call("TodoWrite"))).action(), "第 3 次");
        assertEquals(LoopGuard.Action.WARN, g.evaluate(List.of(call("TodoWrite"))).action(), "第 4 次");
        assertEquals(LoopGuard.Action.ESCALATE, g.evaluate(List.of(call("TodoWrite"))).action(), "第 5 次");
        assertEquals(LoopGuard.Action.ESCALATE, g.evaluate(List.of(call("TodoWrite"))).action(), "第 6 次");
        LoopGuard.Decision d7 = g.evaluate(List.of(call("TodoWrite")));
        assertEquals(LoopGuard.Action.BREAK, d7.action(), "第 7 次");
        assertEquals(7, d7.count());
        assertEquals("TodoWrite", d7.toolName());
        assertTrue(LoopGuard.breakText(d7).contains("[系统熔断]"), "熔断文案");
    }

    @Test
    void mixedRoundDoesNotCountNorReset() {
        LoopGuard g = new LoopGuard(LoopGuardConfig.defaults());
        g.evaluate(List.of(call("TodoWrite")));
        g.evaluate(List.of(call("TodoWrite")));
        // 混合轮：不计数也不重置
        assertEquals(LoopGuard.Action.NONE,
                g.evaluate(List.of(call("TodoWrite"), call("echo"))).action());
        // 穿插后第 3 次纯单工具轮仍触发 WARN（事故实证：context_reload 穿插后又回到 TodoWrite）
        assertEquals(LoopGuard.Action.WARN, g.evaluate(List.of(call("TodoWrite"))).action());
    }

    @Test
    void changedArgsDoNotCountAsRepetition() {
        LoopGuard g = new LoopGuard(LoopGuardConfig.defaults());
        // 同名纯轮但参数变化 = 真实进展（TodoWrite 每步边界更新计划）：不计复读、不重置累计
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite", "{\"i\":1}"))).action(), "第 1 次参数");
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite", "{\"i\":2}"))).action(), "第 2 次参数");
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite", "{\"i\":3}"))).action(), "第 3 次参数");
        // 参数与上次相同恢复计数，累计延续（对齐旧口径"穿插不重置"）：累计到 3 触发 WARN
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite", "{\"i\":3}"))).action(), "相同参数第 1 次（累计 2）");
        assertEquals(LoopGuard.Action.WARN, g.evaluate(List.of(call("TodoWrite", "{\"i\":3}"))).action(), "相同参数第 2 次（累计 3）");
    }

    @Test
    void ignoreToolsNeverTrigger() {
        LoopGuard g = new LoopGuard(new LoopGuardConfig(true, 3, 5, 7, Set.of("executeSql")));
        for (int i = 0; i < 10; i++) {
            assertEquals(LoopGuard.Action.NONE,
                    g.evaluate(List.of(call("executeSql"))).action(), "豁免工具第 " + (i + 1) + " 次");
        }
    }

    @Test
    void disabledConfigNeverTriggers() {
        LoopGuard g = new LoopGuard(LoopGuardConfig.disabled());
        for (int i = 0; i < 10; i++) {
            assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call("TodoWrite"))).action());
        }
    }

    @Test
    void noteTextContainsGuidanceAndNoneIsEmpty() {
        String note = LoopGuard.noteText(new LoopGuard.Decision(LoopGuard.Action.WARN, "TodoWrite", 3));
        assertTrue(note.contains("[系统提示]"));
        assertTrue(note.contains("tool_search"), "应引导发现缺失工具");
        assertTrue(note.contains("3"), "应带累计次数");
        assertEquals("", LoopGuard.noteText(LoopGuard.Decision.NONE));
        String escalate = LoopGuard.noteText(
                new LoopGuard.Decision(LoopGuard.Action.ESCALATE, "TodoWrite", 5));
        assertTrue(escalate.contains("[系统警告]"));
    }

    @Test
    void blankNameAndMixedArgsAreIgnored() {
        LoopGuard g = new LoopGuard(LoopGuardConfig.defaults());
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of(call(""))).action(), "空白工具名不计数");
        assertEquals(LoopGuard.Action.NONE, g.evaluate(List.of()).action(), "空轮不计数");
    }
}

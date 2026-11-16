package com.llmetor.miniagentx.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行计划同步提醒单测：建计划前不提醒 / 每 5 轮提醒一次 / TodoWrite 同步清零（大小写不敏感）。
 */
class PlanSyncReminderTest {

    private static ToolCall call(String name) {
        return new ToolCall("c", "function", name, "{}");
    }

    @Test
    void noReminderBeforePlanCreated() {
        PlanSyncReminder r = new PlanSyncReminder();
        for (int i = 1; i <= 10; i++) {
            assertNull(r.evaluate(List.of(call("executeSql"))), "计划建立前不提醒，第 " + i + " 轮");
        }
    }

    @Test
    void reminderEveryFiveRoundsAndResetOnSync() {
        PlanSyncReminder r = new PlanSyncReminder();
        assertNull(r.evaluate(List.of(call("TodoWrite"))), "建计划轮本身不提醒");
        // 第 1-4 轮不提醒
        for (int i = 1; i <= 4; i++) {
            assertNull(r.evaluate(List.of(call("executeSql"))), "第 " + i + " 轮不提醒");
        }
        // 第 5 轮提醒
        String note = r.evaluate(List.of(call("executeSql")));
        assertNotNull(note, "第 5 轮应提醒");
        assertTrue(note.contains("[系统提醒]"));
        assertTrue(note.contains("5"), "应带未同步轮数");
        // 第 6-9 轮不提醒，第 10 轮再提醒（模型持续忽略则每窗口再提醒）
        for (int i = 6; i <= 9; i++) {
            assertNull(r.evaluate(List.of(call("executeSql"))), "第 " + i + " 轮不提醒");
        }
        assertNotNull(r.evaluate(List.of(call("executeSql"))), "第 10 轮应再提醒");
    }

    @Test
    void syncResetsCounterCaseInsensitive() {
        PlanSyncReminder r = new PlanSyncReminder();
        r.evaluate(List.of(call("TodoWrite")));
        for (int i = 1; i <= 5; i++) {
            r.evaluate(List.of(call("executeSql")));
        }
        // 同步（前端实际事件里工具名大小写不一）：计数清零，重新等满 5 轮
        assertNull(r.evaluate(List.of(call("TodoWrite"))));
        for (int i = 1; i <= 4; i++) {
            assertNull(r.evaluate(List.of(call("executeSql"))), "清零后第 " + i + " 轮不提醒");
        }
        // 混合轮里带 TodoWrite 也算同步
        assertNull(r.evaluate(List.of(call("executeSql"), call("todowrite"))));
        for (int i = 1; i <= 4; i++) {
            assertNull(r.evaluate(List.of(call("executeSql"))), "混合轮同步后第 " + i + " 轮不提醒");
        }
        assertEquals(PlanSyncReminder.REMIND_EVERY_ROUNDS, 5);
    }
}

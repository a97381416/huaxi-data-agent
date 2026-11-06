package com.llmetor.miniagentx.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * context_reload 工具单测（M7-B07 内核侧）：uuid 取回 / 32000 截断 / 未找到提示。
 */
class ContextReloadToolTest {

    private static ToolCallback tool(OffloadStore store, String convId) {
        return ContextReloadTool.build(store, convId);
    }

    private static String call(ToolCallback tool, String uuid) {
        // ToolCallback.call 入参是工具 JSON（对齐真实 LLM 调用形态）
        return tool.call("{\"uuid\":\"" + uuid + "\"}");
    }

    @Test
    void returnsArchivedOriginalByUuid() {
        InMemoryOffloadStore store = new InMemoryOffloadStore();
        List<Message> original = List.of(
                new UserMessage("第一问"),
                new AssistantMessage("查询结果是 42 条记录"));
        store.save("conv_a", "uuid-1", original);

        String out = call(tool(store, "conv_a"), "uuid-1");
        assertNotNull(out);
        assertTrue(out.contains("第一问"));
        assertTrue(out.contains("42 条记录"));
    }

    @Test
    void unknownUuidGetsHonestHint() {
        String out = call(tool(new InMemoryOffloadStore(), "conv_b"), "not-exist");
        assertTrue(out.contains("未找到"));
        assertTrue(out.contains("not-exist"));
    }

    @Test
    void blankUuidGetsParamHint() {
        String out = call(tool(new InMemoryOffloadStore(), "conv_c"), " ");
        assertTrue(out.contains("参数错误"));
    }

    @Test
    void longContentTruncatedAtThirtyTwoThousandChars() {
        InMemoryOffloadStore store = new InMemoryOffloadStore();
        store.save("conv_d", "uuid-big", List.of(new AssistantMessage("字".repeat(40_000))));

        String out = call(tool(store, "conv_d"), "uuid-big");
        assertTrue(out.length() <= ContextReloadTool.MAX_CHARS + 60,
                "截断后不超过 32000+提示长度: " + out.length());
        assertTrue(out.contains("已截断"));
    }

    @Test
    void conversationIsolation() {
        InMemoryOffloadStore store = new InMemoryOffloadStore();
        store.save("conv_x", "uuid-x", List.of(new UserMessage("X 会话内容")));
        // 换会话查同一个 uuid → 取不到
        String out = call(tool(store, "conv_y"), "uuid-x");
        assertTrue(out.contains("未找到"));
    }
}

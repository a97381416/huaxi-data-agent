package com.llmetor.miniagentx.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageJsonSerializer 往返测试：assistant 带 toolCalls+reasoning、
 * tool 消息展开/连续合并回 ToolResponseMessage。
 */
class MessageJsonSerializerTest {

    private final MessageJsonSerializer serializer = new MessageJsonSerializer();

    @Test
    void roundTripAssistantWithToolCallsAndReasoning() {
        AssistantMessage am = AssistantMessage.builder()
                .content("正文")
                .properties(Map.of(MessageJsonSerializer.KEY_REASONING, "思考内容"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"text\":\"hi\"}")))
                .build();
        List<Message> back = serializer.fromJsonArray(serializer.toJsonArray(List.of(am)));

        assertEquals(1, back.size());
        AssistantMessage restored = assertInstanceOf(AssistantMessage.class, back.get(0));
        assertEquals("正文", restored.getText());
        assertEquals("思考内容", restored.getMetadata().get(MessageJsonSerializer.KEY_REASONING));
        assertEquals(1, restored.getToolCalls().size());
        assertEquals("echo", restored.getToolCalls().get(0).name());
        assertEquals("{\"text\":\"hi\"}", restored.getToolCalls().get(0).arguments());
    }

    @Test
    void toolMessageExpandsAndMerges() {
        // 一个 ToolResponseMessage 含 2 个响应 → 展开 2 行 → 反序列化合并回 1 条
        ToolResponseMessage trm = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "echo", "r1"),
                new ToolResponseMessage.ToolResponse("c2", "boom", "r2"))).build();
        List<Message> back = serializer.fromJsonArray(serializer.toJsonArray(List.of(trm)));

        assertEquals(1, back.size());
        ToolResponseMessage merged = assertInstanceOf(ToolResponseMessage.class, back.get(0));
        assertEquals(2, merged.getResponses().size());
        assertEquals("c1", merged.getResponses().get(0).id());
        assertEquals("r2", merged.getResponses().get(1).responseData());
    }

    @Test
    void consecutiveToolRowsMerge() {
        String json = "[" +
                "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"name\":\"a\",\"content\":\"x\"}," +
                "{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"name\":\"b\",\"content\":\"y\"}" +
                "]";
        List<Message> back = serializer.fromJsonArray(json);
        assertEquals(1, back.size());
        assertEquals(2, ((ToolResponseMessage) back.get(0)).getResponses().size());
    }

    @Test
    void fullChainRoundTrip() {
        List<Message> chain = List.of(
                new SystemMessage("sys"),
                new UserMessage("问"),
                AssistantMessage.builder().content("答")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "echo", "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "echo", "ok"))).build(),
                new AssistantMessage("最终"));
        List<Message> back = serializer.fromJsonArray(serializer.toJsonArray(chain));
        assertEquals(5, back.size());
        assertEquals("sys", back.get(0).getText());
        assertTrue(back.get(2) instanceof AssistantMessage);
        assertTrue(back.get(3) instanceof ToolResponseMessage);
        assertEquals("最终", back.get(4).getText());
    }

    @Test
    void badJsonReturnsEmpty() {
        assertTrue(serializer.fromJsonArray("not json").isEmpty());
        assertTrue(serializer.fromJsonArray(null).isEmpty());
    }
}

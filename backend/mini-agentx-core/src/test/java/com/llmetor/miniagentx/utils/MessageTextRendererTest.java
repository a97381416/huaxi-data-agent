package com.llmetor.miniagentx.utils;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息文本渲染器单测（Fix E）：工具类消息按类型展开（复现"context_reload 取回 144 个换行"缺陷的修复面）。
 */
class MessageTextRendererTest {

    @Test
    void assistantToolCallRoundRendersCallsAndReasoning() {
        AssistantMessage am = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", "想先查草稿"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "c1", "function", "listMyDrafts", "{\"kw\":\"人事管理\"}")))
                .build();
        String out = MessageTextRenderer.renderMessage(am);
        assertTrue(out.contains("[思考] 想先查草稿"), "思考应渲染: " + out);
        assertTrue(out.contains("[调用 listMyDrafts] {\"kw\":\"人事管理\"}"), "工具调用应渲染: " + out);
    }

    @Test
    void toolResponseRendersResponseDataNotJustGetText() {
        ToolResponseMessage trm = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "c1", "executeSql", "{\"rows\":42}")))
                .build();
        String out = MessageTextRenderer.renderMessage(trm);
        assertTrue(out.contains("[executeSql 结果] {\"rows\":42}"), "结果数据应渲染: " + out);
        assertTrue(!out.isBlank(), "不应为空白（原缺陷：只读 getText() 得空）");
    }

    @Test
    void plainMessagesRenderText() {
        assertEquals("你好", MessageTextRenderer.renderMessage(new UserMessage("你好")));
        assertEquals("答案", MessageTextRenderer.renderMessage(new AssistantMessage("答案")));
    }

    @Test
    void renderListJoinsAllMessages() {
        String out = MessageTextRenderer.render(
                List.of(new UserMessage("a"), new AssistantMessage("b")));
        assertTrue(out.contains("a"));
        assertTrue(out.contains("b"));
    }

    @Test
    void nullAndEmptySafe() {
        assertEquals("", MessageTextRenderer.render(null));
        assertEquals("", MessageTextRenderer.render(List.of()));
        assertEquals("", MessageTextRenderer.renderMessage(null));
    }
}

package com.llmetor.miniagentx.interrupt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.model.PauseState;
import com.llmetor.miniagentx.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JdbcPauseStateStore 的 JSON 序列化往返（不连库）：快照落库/回读的字段完整性。
 */
class JdbcPauseStateStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pauseStateJsonRoundTrip() throws Exception {
        RunnableParams params = RunnableParams.builder()
                .conversationId("conv_rt")
                .userId("42")
                .toolParams(java.util.Map.of("userId", "42"))
                .build();
        PauseState state = new PauseState(
                "conv_rt", "sess_1", "分析一下销量",
                params,
                List.of("{\"role\":\"user\",\"content\":\"分析一下销量\"}",
                        "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"name\":\"executeSql\",\"arguments\":\"{}\"}]}"),
                2,
                List.of(new PauseState.PendingToolCall("c1", "executeSql", "{}")),
                120, 45,
                PauseState.Reason.USER_INTERRUPT,
                PauseState.SafePoint.TOOL_EXECUTION,
                "用户叫停",
                1760000000000L);

        String json = MAPPER.writeValueAsString(JdbcPauseStateStore.toMap(state));
        PauseState restored = JdbcPauseStateStore.fromMap(
                MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
                }));

        assertEquals(state.getConversationId(), restored.getConversationId());
        assertEquals(state.getSessionId(), restored.getSessionId());
        assertEquals(state.getQuery(), restored.getQuery());
        assertEquals(state.getMessagesJson(), restored.getMessagesJson());
        assertEquals(state.getCurrentRound(), restored.getCurrentRound());
        assertEquals(state.getPendingToolCalls(), restored.getPendingToolCalls());
        assertEquals(120, restored.getTotalPromptTokens());
        assertEquals(45, restored.getTotalCompletionTokens());
        assertEquals(PauseState.Reason.USER_INTERRUPT, restored.getReason());
        assertEquals(PauseState.SafePoint.TOOL_EXECUTION, restored.getSafePoint());
        assertEquals("用户叫停", restored.getInterruptMessage());
        assertEquals(1760000000000L, restored.getInterruptedAt());
        assertEquals("conv_rt", restored.getParams().getConversationId());
        assertEquals("42", restored.getParams().getUserId());
        assertEquals(Map.of("userId", "42"), restored.getParams().getToolParams());
    }
}

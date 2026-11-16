package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.utils.MessageTextRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fix B 历史扫描单测：有限名单文本匹配——覆盖 L1 压缩标记 / 思考提及 / 结构化字段 /
 * 未提及不恢复（恢复不变量："模型当前看得见的才恢复"）。
 */
class RestoreScanTest {

    private static final List<String> CANDIDATES = List.of(
            "listMyDrafts", "fillFileEntry", "submitLedgerForReview", "approveTask");

    @Test
    void findsNamesInOffloadMarkerTextReasoningAndStructured() {
        // ① L1 压缩标记：工具名以纯文本存活（结构化字段已丢失）
        Message marker = new UserMessage("- listMyDrafts({\"kw\":\"人事…\") → 结果预览…\n"
                + "…[本条原文已压缩 offload，uuid=7f521444；调用 context_reload 可取回]");
        // ② 思考里提及（模型可见 reasoning_content）
        Message reasoning = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", "先调 submitLedgerForReview 提审"))
                .build();
        // ③ 结构化字段（未压缩区域）
        Message structured = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "fillFileEntry", "{}")))
                .build();
        Message toolResp = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "fillFileEntry", "ok")))
                .build();

        Set<String> hit = AgentLoopExecutor.scanDeferredToolNames(
                List.of(marker, reasoning, structured, toolResp), CANDIDATES);

        assertTrue(hit.contains("listMyDrafts"), "压缩标记文本里的名字应命中: " + hit);
        assertTrue(hit.contains("submitLedgerForReview"), "思考提及的名字应命中: " + hit);
        assertTrue(hit.contains("fillFileEntry"), "结构化字段应命中: " + hit);
        assertFalse(hit.contains("approveTask"), "从未出现的名字不应命中");
    }

    @Test
    void neverMentionedNameIsNotRestored() {
        // L4 摘要完全没提该工具（恢复不变量：模型不知道的也不恢复）
        Message summary = new UserMessage("[轮次摘要] 用户确认了新增自定义类别行并完成暂存。");
        Set<String> hit = AgentLoopExecutor.scanDeferredToolNames(List.of(summary), CANDIDATES);
        assertTrue(hit.isEmpty(), "无任何名字出现时扫描应为空: " + hit);
    }

    @Test
    void emptyInputsReturnEmpty() {
        assertTrue(AgentLoopExecutor.scanDeferredToolNames(List.of(), CANDIDATES).isEmpty());
        assertTrue(AgentLoopExecutor.scanDeferredToolNames(
                List.of(new UserMessage("listMyDrafts")), List.of()).isEmpty());
    }
}

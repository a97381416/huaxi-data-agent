package com.llmetor.miniagentx.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩策略链单测（M7-B 组）：触发阈值 / L1 工具区 offload / L2 大消息 offload /
 * L4 轮次摘要 / 保护区不误伤 / 策略先触发者赢。
 */
class ContextCompactorTest {

    private static final InMemoryOffloadStore STORE = new InMemoryOffloadStore();

    /** 小阈值策略：让短链也能进历史区（生产默认 lastKeep=50） */
    private static ContextPolicy smallPolicy() {
        return ContextPolicy.builder()
                .lastKeep(2)
                .msgThreshold(10)
                .tokenThreshold(90_000)
                .minConsecutiveToolMessages(3)
                .minCompressionTokens(500)
                .largePayloadTokens(300)
                .offloadPreviewChars(50)
                .maxLlmCompressionCount(3)
                .build();
    }

    /** 固定返回摘要文本的 ChatModel（L4 用） */
    private static ChatModel summaryModel(String summary) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(summary))));
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return reactor.core.publisher.Flux.just(call(prompt));
            }
        };
    }

    // ==================== 构链辅助 ====================

    /** 一轮工具调用：assistant(带 toolCalls) + 一条批量 ToolResponseMessage */
    private static List<Message> toolRound(String id, String toolName, String args, String resultPayload) {
        AssistantMessage am = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, args)))
                .build();
        ToolResponseMessage trm = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, toolName, resultPayload)))
                .build();
        return List.of(am, trm);
    }

    private static String bigPayload(String tag, int cjkChars) {
        // cjkChars 个汉字 ≈ cjkChars/1.5 token
        StringBuilder sb = new StringBuilder(tag);
        sb.append("数".repeat(Math.max(0, cjkChars)));
        return sb.toString();
    }

    /** 历史区工具密集链：system + user1 + 3 轮工具 + user2 + 纯文本 assistant + user3(保护区) */
    private static List<Message> toolHeavyChain() {
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.add(new UserMessage("第一问：查数据"));
        for (int i = 1; i <= 3; i++) {
            chain.addAll(toolRound("c" + i, "executeSql", "{\"sql\":\"SELECT " + i + "\"}", bigPayload("R" + i, 1200)));
        }
        chain.add(new UserMessage("第二问：总结"));
        chain.add(new AssistantMessage("第二答：已总结"));
        chain.add(new UserMessage("第三问：当前问题"));
        return chain;
    }

    // ==================== 触发阈值 ====================

    @Test
    void shouldCompactOnMessageCountOrTokenThresholdOnly() {
        ContextCompactor compactor = new ContextCompactor(smallPolicy(), STORE, null);

        assertFalse(compactor.shouldCompact(List.of(new SystemMessage("s"), new UserMessage("q"))));

        // 消息数达到 msgThreshold=10
        List<Message> many = new ArrayList<>();
        many.add(new SystemMessage("s"));
        for (int i = 0; i < 10; i++) {
            many.add(new UserMessage("q" + i));
        }
        assertTrue(compactor.shouldCompact(many));

        // token 达到阈值（策略调成小 token 阈值验证）
        ContextPolicy tokenPolicy = ContextPolicy.builder().msgThreshold(1000).tokenThreshold(100).build();
        ContextCompactor tokenCompactor = new ContextCompactor(tokenPolicy, STORE, null);
        List<Message> bigToken = List.of(
                new SystemMessage("s"),
                new UserMessage(bigPayload("x", 200))); // 200/1.5 ≈ 133 ≥ 100
        assertTrue(tokenCompactor.shouldCompact(bigToken));
    }

    // ==================== L1：工具密集区 offload ====================

    @Test
    void l1OffloadsToolRegionAndKeepsProtectedZone() {
        ContextCompactor compactor = new ContextCompactor(smallPolicy(), STORE, null);
        List<Message> chain = toolHeavyChain(); // 11 条 ≥ msgThreshold=10，lastKeep=2 → 历史区 [1,9)
        assertTrue(compactor.shouldCompact(chain));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_l1", chain);
        assertTrue(outcome.result().compacted(), "L1 应触发");
        assertEquals("L1", outcome.result().strategy());

        List<Message> newChain = outcome.newChain();
        // 工具区 6 条（3× assistant+tool）被替换为 1 条模板 user 消息
        assertTrue(newChain.stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "历史工具响应应全部被 offload");
        // 模板消息带 uuid 标记
        Message marker = newChain.stream()
                .filter(m -> m instanceof UserMessage u && u.getText().contains("uuid="))
                .findFirst().orElse(null);
        assertNotNull(marker, "应存在带 uuid= 标记的替换消息");
        String text = ((UserMessage) marker).getText();
        assertTrue(text.contains("历史工具调用记录"), "模板应有标题: " + text);
        assertTrue(text.contains("executeSql"), "模板应列工具名: " + text);
        // uuid 可从 offload store 取回原文（6 条：3 assistant + 3 tool）
        String uuid = extractUuid(text);
        List<Message> offloaded = STORE.load("conv_l1", uuid);
        assertNotNull(offloaded, "offload 原文应已归档");
        assertEquals(6, offloaded.size());
        // 保护区：最后 2 条（第二答、第三问）原样保留
        assertEquals("第三问：当前问题", ((UserMessage) newChain.get(newChain.size() - 1)).getText());
        assertEquals("第二答：已总结", newChain.get(newChain.size() - 2).getText());
    }

    @Test
    void l1SkipsRegionCrossingProtectedBoundary() {
        // 工具区一直延伸到链尾（保护区里也是工具消息）→ 整段跳过，不产生悬挂配对
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(2).tokenThreshold(90_000)
                .minConsecutiveToolMessages(1).minCompressionTokens(10)
                .largePayloadTokens(10_000).maxLlmCompressionCount(3)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, null);
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.addAll(toolRound("c1", "executeSql", "{}", bigPayload("R", 100)));
        chain.addAll(toolRound("c2", "executeSql", "{}", bigPayload("R", 100)));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_cross", chain);
        assertFalse(outcome.result().compacted(), "跨边界区块应整段跳过");
    }

    // ==================== L2：大消息 offload ====================

    @Test
    void l2OffloadsSingleLargeMessagesWithPreview() {
        // 无工具链（L1 不适用）→ L2 兜住单条大 user 消息
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(3).tokenThreshold(90_000)
                .minConsecutiveToolMessages(6).minCompressionTokens(5_000)
                .largePayloadTokens(50).offloadPreviewChars(20).maxLlmCompressionCount(3)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, null);
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.add(new UserMessage("小消息"));
        chain.add(new UserMessage(bigPayload("大消息头", 200))); // ≈133 token > 50
        chain.add(new UserMessage("当前问题"));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_l2", chain);
        assertTrue(outcome.result().compacted());
        assertEquals("L2", outcome.result().strategy());

        List<Message> newChain = outcome.newChain();
        UserMessage replaced = (UserMessage) newChain.get(2);
        String text = replaced.getText();
        assertTrue(text.contains("大消息头"), "预览应保留开头: " + text);
        assertTrue(text.contains("uuid="), "应带 uuid 标记");
        assertTrue(text.length() < 200, "应替换为预览而非原文: len=" + text.length());
        List<Message> offloaded = STORE.load("conv_l2", extractUuid(text));
        assertNotNull(offloaded);
        assertEquals(bigPayload("大消息头", 200), offloaded.get(0).getText(), "归档应为原文");
    }

    @Test
    void l2NeverTouchesAssistantWithToolCalls() {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(3).tokenThreshold(90_000)
                .minConsecutiveToolMessages(99).minCompressionTokens(5_000) // L1 永不触发
                .largePayloadTokens(50).offloadPreviewChars(20).maxLlmCompressionCount(3)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, null);
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        // 大 args 的 assistant(toolCalls) + 小 tool 响应（配对保留在原位）
        chain.addAll(toolRound("c1", "executeSql", "{\"sql\":\"" + "长".repeat(150) + "\"}", "ok"));
        chain.add(new UserMessage("当前问题"));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_l2b", chain);
        assertFalse(outcome.result().compacted(), "带 toolCalls 的 assistant 不可 offload，L2 不应触发");
    }

    // ==================== L4：历史轮次摘要 ====================

    @Test
    void l4SummarizesHistoricalRoundsKeepingUserWords() {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(3).tokenThreshold(90_000)
                .minConsecutiveToolMessages(99).minCompressionTokens(100)
                .largePayloadTokens(10_000).maxLlmCompressionCount(3)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, summaryModel("【摘要】用户查过租金月度趋势，结论是 5 月最高。"));

        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.add(new UserMessage("帮我查租金月度趋势并详细解释背景假设"));
        chain.add(new AssistantMessage("好的一涟假设说明".repeat(30))); // ≥100 token 的轮内容
        chain.add(new UserMessage("当前问题"));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_l4", chain);
        assertTrue(outcome.result().compacted());
        assertEquals("L4", outcome.result().strategy());

        List<Message> newChain = outcome.newChain();
        // user 原话保留 + 摘要 assistant 替换轮内其余消息
        assertEquals("帮我查租金月度趋势并详细解释背景假设", ((UserMessage) newChain.get(1)).getText());
        Message summaryMsg = newChain.get(2);
        assertTrue(summaryMsg instanceof AssistantMessage);
        assertTrue(((AssistantMessage) summaryMsg).getText().contains("【摘要】"));
        assertTrue(((AssistantMessage) summaryMsg).getText().contains("uuid="));
        // 归档含整轮原文
        List<Message> offloaded = STORE.load("conv_l4", extractUuid(((AssistantMessage) summaryMsg).getText()));
        assertNotNull(offloaded);
        assertEquals(2, offloaded.size());
    }

    @Test
    void l4RespectsMaxLlmCompressionCount() {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(3).tokenThreshold(90_000)
                .minConsecutiveToolMessages(99).minCompressionTokens(50)
                .largePayloadTokens(10_000).maxLlmCompressionCount(2)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, summaryModel("【摘要】x"));

        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        for (int r = 1; r <= 4; r++) {
            String filler = "，补充足够长的内容让整轮超过最小压缩 token 要求线来满足触发条件";
            chain.add(new UserMessage(("历史问题" + r + filler).repeat(3)));
            chain.add(new AssistantMessage(("历史回答" + r + filler).repeat(3)));
        }
        chain.add(new UserMessage("当前问题"));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_l4b", chain);
        assertTrue(outcome.result().compacted());
        // 4 个达标轮最多摘要 2 个（从最近往前）→ 剩 2 个完整轮 + 2 个摘要
        long summaries = outcome.newChain().stream()
                .filter(m -> m instanceof AssistantMessage am && am.getText().contains("【摘要】")).count();
        assertEquals(2, summaries);
    }

    // ==================== 策略顺序：先触发者赢 ====================

    @Test
    void l1WinsBeforeL2WhenBothApplicable() {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1).msgThreshold(2).tokenThreshold(90_000)
                .minConsecutiveToolMessages(1).minCompressionTokens(10)
                .largePayloadTokens(10).offloadPreviewChars(20).maxLlmCompressionCount(3)
                .build();
        ContextCompactor compactor = new ContextCompactor(policy, STORE, summaryModel("【摘要】"));
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.add(new UserMessage("问"));
        chain.addAll(toolRound("c1", "executeSql", "{}", bigPayload("R", 100))); // 工具区达标也含大消息
        chain.add(new UserMessage("当前问题"));

        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_order", chain);
        assertTrue(outcome.result().compacted());
        assertEquals("L1", outcome.result().strategy(), "L1 先于 L2 触发");
    }

    @Test
    void belowThresholdNeverCompacts() {
        ContextCompactor compactor = new ContextCompactor(smallPolicy(), STORE, summaryModel("【摘要】"));
        // 2 轮工具 = 9 条 < msgThreshold=10
        List<Message> chain = new ArrayList<>();
        chain.add(new SystemMessage("sys"));
        chain.add(new UserMessage("第一问"));
        chain.addAll(toolRound("c1", "executeSql", "{}", bigPayload("R1", 1200)));
        chain.addAll(toolRound("c2", "executeSql", "{}", bigPayload("R2", 1200)));
        chain.add(new AssistantMessage("回答"));
        chain.add(new UserMessage("当前问题"));
        ContextCompactor.CompactOutcome outcome = compactor.tryCompact("conv_none", chain);
        assertFalse(outcome.result().compacted());
        assertEquals(chain, outcome.newChain(), "未触发时链保持原样");
    }

    // ==================== 小工具 ====================

    private static String extractUuid(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("uuid=([0-9a-f-]{36})").matcher(text);
        assertTrue(m.find(), "文本应含 uuid 标记: " + text);
        return m.group(1);
    }
}

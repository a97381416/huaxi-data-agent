package com.llmetor.miniagentx.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenEstimator 单测（M7-B08）：CJK/非CJK 计价、消息子类型统计、与标尺误差。
 */
class TokenEstimatorTest {

    @Test
    void cjkTextEstimatedAtOnePointFivePerChar() {
        // 30 个汉字 → 30/1.5 = 20 token
        String text = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        assertEquals(30, text.length());
        assertEquals(20, TokenEstimator.estimateText(text));
    }

    @Test
    void latinTextEstimatedAtFourCharsPerToken() {
        // 40 个 ASCII 字符 → 40/4 = 10 token
        String text = "abcdefghij".repeat(4);
        assertEquals(10, TokenEstimator.estimateText(text));
    }

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, TokenEstimator.estimateText(null));
        assertEquals(0, TokenEstimator.estimateText(""));
        assertEquals(0, TokenEstimator.estimate(null));
    }

    @Test
    void assistantCountsToolCallNameAndArguments() {
        // 正文 15 汉字=10；name "executeSql" 10 ASCII=(10+3)/4=3；args `{"a":12345}` 11 ASCII=(11+3)/4=3 → 16
        AssistantMessage am = AssistantMessage.builder()
                .content("一二三四五六七八九十一二三四五")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "executeSql", "{\"a\":12345}")))
                .build();
        assertEquals(10 + 3 + 3, TokenEstimator.estimate(am));
    }

    @Test
    void toolMessageCountsAllResponses() {
        ToolResponseMessage trm = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "executeSql", "一二三四五六"), // 6CJK=4 + name 3
                        new ToolResponseMessage.ToolResponse("c2", "echo", "abcdef")))            // 6ASCII=2 + name 1
                .build();
        assertEquals(4 + 3 + 2 + 1, TokenEstimator.estimate(trm));
    }

    @Test
    void userMessageCountsText() {
        assertEquals(4, TokenEstimator.estimate(new UserMessage("一二三四五六"))); // 6/1.5=4
    }

    @Test
    void mixedTextAndEstimateAll() {
        List<org.springframework.ai.chat.messages.Message> chain = List.of(
                new org.springframework.ai.chat.messages.SystemMessage("abc"), // 1
                new UserMessage("一二三"),                                      // 2
                new AssistantMessage("abcd"));                                  // 1
        assertEquals(4, TokenEstimator.estimateAll(chain));
    }

    @Test
    void estimateWithinTwentyPercentOfReferenceTokenizerScale() {
        // 与"标尺"对比：真实 tokenizer 对中文 ≈0.6~0.7 token/字、英文 ≈0.25 token/字符，
        // 我们的 1/1.5≈0.67 与 1/4=0.25 落在同档——构造混合文本验证量级
        String mixed = "数据分析报告 data analysis report 数据分析报告 data analysis report";
        long estimated = TokenEstimator.estimateText(mixed);
        // 手工标尺：12 汉字×0.65 + 36 ASCII×0.25 ≈ 7.8 + 9 = 16.8 → ±20% = [13.4, 20.2]
        assertTrue(estimated >= 13 && estimated <= 21,
                "估算 %d 应落在标尺 16.8 的 ±20%% 内".formatted(estimated));
    }
}

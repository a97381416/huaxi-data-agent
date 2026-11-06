package com.llmetor.miniagentx.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 零依赖 token 估算器（docs/02 §4.2）：{@code tokens = CJK字符/1.5 + 非CJK字符/4}。
 *
 * <p>按消息子类型统计：
 * <ul>
 *   <li>assistant：正文 + toolCall 的 name/arguments</li>
 *   <li>tool：全部 responses 的 responseData</li>
 *   <li>user / system：正文</li>
 * </ul>
 *
 * <p>估算用途是"触发阈值判断 + 段大小比较"，不追求 tokenizer 级精度
 * （验收口径：与真实 tokenizer 对标误差 ±20% 以内）。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 单条消息估算 token 数 */
    public static long estimate(Message msg) {
        if (msg == null) {
            return 0;
        }
        if (msg instanceof AssistantMessage am) {
            long n = estimateText(am.getText());
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    n += estimateText(tc.name()) + estimateText(tc.arguments());
                }
            }
            return n;
        }
        if (msg instanceof ToolResponseMessage trm) {
            long n = 0;
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    n += estimateText(r.responseData()) + estimateText(r.name());
                }
            }
            return n;
        }
        // user / system / 其它：正文
        return estimateText(safeText(msg));
    }

    /** 消息链总估算 token 数 */
    public static long estimateAll(Iterable<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            total += estimate(m);
        }
        return total;
    }

    /** 混合文本估算：CJK 字符按 1.5 token/字，其余按 4 字符/token */
    public static long estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.round(cjk / 1.5) + (other + 3) / 4;
    }

    /** CJK 统一表意文字区（含扩展 A 常用段；够阈值估算用） */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static String safeText(Message msg) {
        if (msg instanceof SystemMessage sm) {
            return sm.getText();
        }
        if (msg instanceof UserMessage um) {
            return um.getText();
        }
        try {
            return msg.getText();
        } catch (Exception e) {
            return "";
        }
    }
}

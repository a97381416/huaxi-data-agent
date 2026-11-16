package com.llmetor.miniagentx.utils;

import com.llmetor.miniagentx.memory.MessageJsonSerializer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

/**
 * 消息文本渲染器（2026-11-12 事故修复 Fix E）：把 Spring AI 消息按类型展开为可读文本。
 *
 * <p>背景：{@code ContextReloadTool} 原实现只拼 {@code getText()}，而工具类消息的文本
 * 天生为空——工具调用轮 assistant 正文按输出纪律为空（真实内容在 toolCalls 里）、
 * ToolResponseMessage 的数据在 responses 里——导致取回"144 个换行"的空回包（trace 实证）。
 * 本类按消息类型展开：assistant 的思考/正文/工具调用、tool 的逐条结果、其余消息的正文。
 *
 * <p>供 context_reload 取回渲染（Fix E）与延迟工具发现态的历史扫描（Fix B）共用；
 * {@code TraceQueryService.segments()}（server 侧四段式渲染范本）后续可选改造复用。
 */
public final class MessageTextRenderer {

    private MessageTextRenderer() {
    }

    /** 渲染消息列表（结果 strip 首尾空白；空列表返回空串） */
    public static String render(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            appendMessage(sb, m);
        }
        return sb.toString().strip();
    }

    /** 渲染单条消息（结果 strip 首尾空白，永不为 null） */
    public static String renderMessage(Message m) {
        StringBuilder sb = new StringBuilder();
        appendMessage(sb, m);
        return sb.toString().strip();
    }

    /** 按消息类型追加可读文本（思考 / 正文 / 工具调用 / 工具结果） */
    public static void appendMessage(StringBuilder sb, Message m) {
        if (m == null) {
            return;
        }
        if (m instanceof AssistantMessage am) {
            String reasoning = reasoningOf(am);
            if (!reasoning.isBlank()) {
                sb.append("[思考] ").append(reasoning.strip()).append('\n');
            }
            String text = am.getText();
            if (text != null && !text.isBlank()) {
                sb.append(text.strip()).append('\n');
            }
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    sb.append("[调用 ").append(tc.name()).append("] ")
                            .append(tc.arguments() == null ? "" : tc.arguments()).append('\n');
                }
            }
        } else if (m instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    sb.append('[').append(r.name()).append(" 结果] ")
                            .append(r.responseData() == null ? "" : r.responseData()).append('\n');
                }
            }
        } else {
            String text = m.getText();
            if (text != null && !text.isBlank()) {
                sb.append(text.strip()).append('\n');
            }
        }
    }

    /** 从 AssistantMessage metadata 提取模型思考（兼容两种 key，对齐 TraceQueryService） */
    public static String reasoningOf(AssistantMessage am) {
        Map<String, Object> meta = am.getMetadata();
        if (meta == null || meta.isEmpty()) {
            return "";
        }
        Object rc = meta.get(MessageJsonSerializer.KEY_REASONING);
        if (rc == null) {
            rc = meta.get("reasoning_content");
        }
        return rc instanceof String s ? s : "";
    }
}

package com.llmetor.miniagentx.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

/**
 * 流式事件模型（7 种，对齐原框架 AgentStreamEvent）。
 *
 * <p>每个流必以 Paused 或 Complete 收尾。事件经 Sinks 发出，序列化为
 * {@code {"type":"THINKING","content":"..."}} 风格 JSON 给前端。
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentStreamEvent {

    public enum Type {
        /** 思考增量 */
        THINKING,
        /** 正文增量 */
        TEXT,
        /** 工具开始（多工具并发时可能交错） */
        TOOL_START,
        /** 工具结束（全部工具完成后按顺序统一发；失败不发） */
        TOOL_END,
        /** 暂停（HITL 或用户中断），发完立即 complete */
        PAUSED,
        /** 错误（LLM 异常，重试中每次都发） */
        ERROR,
        /** 终态（成功/失败都发） */
        COMPLETE
    }

    private final Type type;
    private final String content;
    private final String toolName;
    private final String toolCallId;
    private final String arguments;
    private final String result;
    private final String code;
    private final String message;
    private final String detail;
    @JsonProperty("pauseReason")
    private final String pauseReason;
    private final Long totalPromptTokens;
    private final Long totalCompletionTokens;
    private final String conversationId;
    private final String sessionId;

    private AgentStreamEvent(Type type, String content, String toolName, String toolCallId,
                             String arguments, String result, String code, String message,
                             String detail, String pauseReason, Long totalPromptTokens,
                             Long totalCompletionTokens, String conversationId, String sessionId) {
        this.type = type;
        this.content = content;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.arguments = arguments;
        this.result = result;
        this.code = code;
        this.message = message;
        this.detail = detail;
        this.pauseReason = pauseReason;
        this.totalPromptTokens = totalPromptTokens;
        this.totalCompletionTokens = totalCompletionTokens;
        this.conversationId = conversationId;
        this.sessionId = sessionId;
    }

    public static AgentStreamEvent thinking(String delta) {
        return new AgentStreamEvent(Type.THINKING, delta, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static AgentStreamEvent text(String delta) {
        return new AgentStreamEvent(Type.TEXT, delta, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static AgentStreamEvent toolStart(String toolName, String toolCallId, String arguments) {
        return new AgentStreamEvent(Type.TOOL_START, null, toolName, toolCallId, arguments, null, null, null, null, null,
                null, null, null, null);
    }

    public static AgentStreamEvent toolEnd(String toolName, String toolCallId, String result) {
        return new AgentStreamEvent(Type.TOOL_END, null, toolName, toolCallId, null, result, null, null, null, null,
                null, null, null, null);
    }

    public static AgentStreamEvent error(String code, String message, String detail) {
        return new AgentStreamEvent(Type.ERROR, null, null, null, null, null, code, message, detail, null,
                null, null, null, null);
    }

    public static AgentStreamEvent paused(String reason) {
        return new AgentStreamEvent(Type.PAUSED, null, null, null, null, null, null, null, null, reason,
                null, null, null, null);
    }

    /**
     * 带 sessionId 的 Paused（M5）：文件-会话双阶段关联需要在被打断的轮次上补写
     * agentx_file.session_id（历史回放按轮取附件），业务层从事件直接取，不必查快照表。
     */
    public static AgentStreamEvent paused(String reason, String sessionId) {
        return new AgentStreamEvent(Type.PAUSED, null, null, null, null, null, null, null, null, reason,
                null, null, null, sessionId);
    }

    public static AgentStreamEvent complete(long totalPromptTokens, long totalCompletionTokens,
                                            String conversationId, String sessionId) {
        return new AgentStreamEvent(Type.COMPLETE, null, null, null, null, null, null, null, null, null,
                totalPromptTokens, totalCompletionTokens, conversationId, sessionId);
    }
}

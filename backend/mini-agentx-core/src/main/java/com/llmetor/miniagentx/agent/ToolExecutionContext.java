package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;

import java.util.function.Consumer;

/**
 * 工具执行线程上下文（一期 HITL）：透传主会话 SSE 发射器与 rootConversationId 等信息，
 * 使工具（含子 Agent 内工具）无需修改 LLM 工具参数签名即可向主会话流推送 HITL 事件。
 */
public final class ToolExecutionContext {

    public record ContextInfo(
            String rootConversationId,
            String conversationId,
            String toolCallId,
            String agentCode,
            Consumer<AgentStreamEvent> stepEmitter) {
    }

    private static final ThreadLocal<ContextInfo> HOLDER = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void set(RunnableParams params, String conversationId, String toolCallId,
                           Consumer<AgentStreamEvent> stepEmitter) {
        String rootConvId = params != null ? params.effectiveRootConversationId() : conversationId;
        String agentCode = params != null ? params.getAgentCode() : null;
        HOLDER.set(new ContextInfo(rootConvId, conversationId, toolCallId, agentCode, stepEmitter));
    }

    public static ContextInfo get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 向当前会话 SSE 流发射事件（子 Agent 内会自动经由 stepEmitter 透传至主会话流） */
    public static boolean emit(AgentStreamEvent event) {
        ContextInfo info = HOLDER.get();
        if (info == null || info.stepEmitter() == null || event == null) {
            return false;
        }
        info.stepEmitter().accept(event);
        return true;
    }

    public static String currentRootConversationId() {
        ContextInfo info = HOLDER.get();
        return info != null ? info.rootConversationId() : null;
    }

    public static String currentToolCallId() {
        ContextInfo info = HOLDER.get();
        return info != null ? info.toolCallId() : null;
    }

    public static String currentAgentCode() {
        ContextInfo info = HOLDER.get();
        return info != null ? info.agentCode() : null;
    }
}

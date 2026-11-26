package com.llmetor.miniagentx.agent;

import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 工具执行线程上下文（一期/二期 HITL）：透传主会话 SSE 发射器、rootConversationId、
 * 串行队列位置（1/3 + 后续排队预览）、挂起预落盘钩子、慢路径恢复决策单 ID 及全部拒绝标记。
 */
public final class ToolExecutionContext {

    /** 二期 HITL：串行队列中的后续排队预览项 */
    public record HitlQueueItem(String toolCallId, String toolName, String arguments) {
    }

    /** 二期 HITL：当前工具在串行审批队列中的位置与后续预览 */
    public record HitlQueueInfo(int index, int total, List<HitlQueueItem> upcoming) {
    }

    public record ContextInfo(
            String rootConversationId,
            String conversationId,
            String toolCallId,
            String agentCode,
            Consumer<AgentStreamEvent> stepEmitter,
            HitlQueueInfo queueInfo,
            String resumeDecisionId,
            Consumer<String> preParkSaver,
            AtomicBoolean rejectAllFlag) {
    }

    private static final ThreadLocal<ContextInfo> HOLDER = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void set(RunnableParams params, String conversationId, String toolCallId,
                           Consumer<AgentStreamEvent> stepEmitter) {
        set(params, conversationId, toolCallId, stepEmitter, null, null, null, null);
    }

    public static void set(RunnableParams params, String conversationId, String toolCallId,
                           Consumer<AgentStreamEvent> stepEmitter,
                           HitlQueueInfo queueInfo,
                           String resumeDecisionId,
                           Consumer<String> preParkSaver,
                           AtomicBoolean rejectAllFlag) {
        String rootConvId = params != null ? params.effectiveRootConversationId() : conversationId;
        String agentCode = params != null ? params.getAgentCode() : null;
        HOLDER.set(new ContextInfo(rootConvId, conversationId, toolCallId, agentCode, stepEmitter,
                queueInfo, resumeDecisionId, preParkSaver, rejectAllFlag));
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

    public static HitlQueueInfo currentQueueInfo() {
        ContextInfo info = HOLDER.get();
        return info != null ? info.queueInfo() : null;
    }

    public static String currentResumeDecisionId() {
        ContextInfo info = HOLDER.get();
        return info != null ? info.resumeDecisionId() : null;
    }

    /** 进入 60s await 前预落盘 PauseState，防等待期间服务重启或宕机丢失现场 */
    public static void savePreParkState(String decisionId) {
        ContextInfo info = HOLDER.get();
        if (info != null && info.preParkSaver() != null && decisionId != null) {
            info.preParkSaver().accept(decisionId);
        }
    }

    /** 标记用户点击了【全部拒绝】，通知 ToolCallExecutor 截断同批次后续工具 */
    public static void markRejectAll() {
        ContextInfo info = HOLDER.get();
        if (info != null && info.rejectAllFlag() != null) {
            info.rejectAllFlag().set(true);
        }
    }
}

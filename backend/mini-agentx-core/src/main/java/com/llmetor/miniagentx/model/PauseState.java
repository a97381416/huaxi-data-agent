package com.llmetor.miniagentx.model;

import lombok.Getter;
import lombok.ToString;

/**
 * 中断快照（M1 字段子集：USER_INTERRUPT 场景）。
 *
 * <p>messagesJson 为完整消息链的逐条 JSON 行（含快照时刻的半截输出与占位工具响应），
 * pendingToolCalls 记录被打断时尚未回灌的工具调用，供恢复时表达"待执行"状态：
 * <ul>
 *   <li>M3 起由 {@code JdbcPauseStateStore} 持久化（agentx_pause_state 表，跨重启可恢复）</li>
 *   <li>恢复语义见 {@code AgentLoopExecutor}：空 query=本地工具重放续跑；带 query=不重放，
 *       注入跳过占位 + 澄清语，由模型自决策</li>
 * </ul>
 */
@Getter
@ToString
public class PauseState {

    /** 暂停原因 */
    public enum Reason {
        /** 用户主动中断 */
        USER_INTERRUPT,
        /** HITL 工具请求（未实现，占位） */
        HITL_TOOL_REQUEST
    }

    /** 中断时所在安全点 */
    public enum SafePoint {
        INIT, LLM_STREAMING, TOOL_EXECUTION
    }

    private final String conversationId;
    private final String sessionId;
    private final String query;
    private final RunnableParams params;
    private final java.util.List<String> messagesJson;
    private final int currentRound;
    private final java.util.List<PendingToolCall> pendingToolCalls;
    private final long totalPromptTokens;
    private final long totalCompletionTokens;
    private final Reason reason;
    private final SafePoint safePoint;
    private final String interruptMessage;
    private final long interruptedAt;

    public PauseState(String conversationId, String sessionId, String query, RunnableParams params,
                      java.util.List<String> messagesJson, int currentRound,
                      java.util.List<PendingToolCall> pendingToolCalls,
                      long totalPromptTokens, long totalCompletionTokens,
                      Reason reason, SafePoint safePoint, String interruptMessage) {
        this(conversationId, sessionId, query, params, messagesJson, currentRound, pendingToolCalls,
                totalPromptTokens, totalCompletionTokens, reason, safePoint, interruptMessage,
                System.currentTimeMillis());
    }

    /** 反序列化通道：interruptedAt 从存储值还原（JdbcPauseStateStore 专用） */
    public PauseState(String conversationId, String sessionId, String query, RunnableParams params,
                      java.util.List<String> messagesJson, int currentRound,
                      java.util.List<PendingToolCall> pendingToolCalls,
                      long totalPromptTokens, long totalCompletionTokens,
                      Reason reason, SafePoint safePoint, String interruptMessage,
                      long interruptedAt) {
        this.conversationId = conversationId;
        this.sessionId = sessionId;
        this.query = query;
        this.params = params;
        this.messagesJson = messagesJson;
        this.currentRound = currentRound;
        this.pendingToolCalls = pendingToolCalls;
        this.totalPromptTokens = totalPromptTokens;
        this.totalCompletionTokens = totalCompletionTokens;
        this.reason = reason;
        this.safePoint = safePoint;
        this.interruptMessage = interruptMessage;
        this.interruptedAt = interruptedAt;
    }

    /**
     * 待执行/未回灌的工具调用（快照可表达"恢复时需重放"状态；工具可能已部分执行副作用，
     * 恢复重放要求工具幂等——原框架同款约束）。
     */
    public record PendingToolCall(String id, String name, String arguments) {
    }
}

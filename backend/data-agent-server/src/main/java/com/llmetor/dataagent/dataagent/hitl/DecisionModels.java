package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一期 HITL 数据模型与协议 DTO。
 */
public final class DecisionModels {

    private DecisionModels() {
    }

    public static final String TYPE_PERMISSION = "PERMISSION";
    public static final String TYPE_QUESTION = "QUESTION";
    public static final String TYPE_VERIFY_OUTCOME = "VERIFY_OUTCOME";
    public static final String TYPE_PLAN_CONFIRM = "PLAN_CONFIRM";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_AUTO_APPROVED_BY_GRANT = "AUTO_APPROVED_BY_GRANT";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    // 三期 HITL 状态
    public static final String STATUS_ANSWERED = "ANSWERED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_TIMED_OUT_SOFT = "TIMED_OUT_SOFT";
    public static final String STATUS_CONFIRMED_SUCCEEDED = "CONFIRMED_SUCCEEDED";
    public static final String STATUS_CONFIRMED_NOT_APPLIED = "CONFIRMED_NOT_APPLIED";
    public static final String STATUS_ABORTED = "ABORTED";
    public static final String STATUS_REVISED = "REVISED";

    public static final String OPTION_ALLOW_ONCE = "ALLOW_ONCE";
    public static final String OPTION_ALLOW_SESSION = "ALLOW_SESSION";
    public static final String OPTION_REJECTED = "REJECTED";
    public static final String OPTION_REJECT_ALL = "REJECT_ALL";
    // 三期 HITL 决策选项
    public static final String OPTION_SUBMIT_ANSWERS = "SUBMIT_ANSWERS";
    public static final String OPTION_SKIP_QUESTION = "SKIP_QUESTION";
    public static final String OPTION_CONFIRMED_SUCCEEDED = "CONFIRMED_SUCCEEDED";
    public static final String OPTION_CONFIRMED_NOT_APPLIED = "CONFIRMED_NOT_APPLIED";
    public static final String OPTION_ABORTED = "ABORTED";
    public static final String OPTION_APPROVE_PLAN = "APPROVE_PLAN";
    public static final String OPTION_REVISE_PLAN = "REVISE_PLAN";

    public static final String MODE_INLINE_RESUMED = "INLINE_RESUMED";
    public static final String MODE_NEED_RESUME = "NEED_RESUME";

    public static final String GRANT_ACTIVE = "ACTIVE";
    public static final String GRANT_REVOKED = "REVOKED";
    public static final String GRANT_EXPIRED = "EXPIRED";

    /** 关键参数展示项 */
    public record KeyParamItem(String label, String value) {
    }

    /** 二期 HITL：串行排队预览项（第 2、3 项只读预览） */
    public record QueuePreviewItem(String toolCallId, String toolName, String label) {
    }

    /** 推送给前端的结构化卡片载荷（对应 AgentStreamEvent.hitlPayload 与接口响应） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HitlCardPayload(
            String decisionId,
            String conversationId,
            String toolCallId,
            String agentCode,
            String type,
            String status,
            int revision,
            String toolName,
            String actionLabel,
            String targetSummary,
            String actionSummary,
            List<KeyParamItem> keyParams,
            Object rawPayload,
            String scopeKey,
            String scopeDesc,
            boolean allowSessionGrant,
            String decisionOption,
            String userWords,
            String grantId,
            String answeredByName,
            String expiresAt,
            String createdAt,
            String answeredAt,
            String consumedAt,
            long remainingSeconds,
            String conversationTitle,
            Integer queueIndex,
            Integer queueTotal,
            List<QueuePreviewItem> queuePreview,
            Object answerJson
    ) {
        public HitlCardPayload(
                String decisionId, String conversationId, String toolCallId, String agentCode,
                String type, String status, int revision, String toolName, String actionLabel,
                String targetSummary, String actionSummary, List<KeyParamItem> keyParams,
                Object rawPayload, String scopeKey, String scopeDesc, boolean allowSessionGrant,
                String decisionOption, String userWords, String grantId, String answeredByName,
                String expiresAt, String createdAt, String answeredAt, String consumedAt,
                long remainingSeconds, String conversationTitle) {
            this(decisionId, conversationId, toolCallId, agentCode, type, status, revision,
                    toolName, actionLabel, targetSummary, actionSummary, keyParams, rawPayload,
                    scopeKey, scopeDesc, allowSessionGrant, decisionOption, userWords, grantId,
                    answeredByName, expiresAt, createdAt, answeredAt, consumedAt, remainingSeconds,
                    conversationTitle, null, null, null, null);
        }

        public HitlCardPayload(
                String decisionId, String conversationId, String toolCallId, String agentCode,
                String type, String status, int revision, String toolName, String actionLabel,
                String targetSummary, String actionSummary, List<KeyParamItem> keyParams,
                Object rawPayload, String scopeKey, String scopeDesc, boolean allowSessionGrant,
                String decisionOption, String userWords, String grantId, String answeredByName,
                String expiresAt, String createdAt, String answeredAt, String consumedAt,
                long remainingSeconds, String conversationTitle,
                Integer queueIndex, Integer queueTotal, List<QueuePreviewItem> queuePreview) {
            this(decisionId, conversationId, toolCallId, agentCode, type, status, revision,
                    toolName, actionLabel, targetSummary, actionSummary, keyParams, rawPayload,
                    scopeKey, scopeDesc, allowSessionGrant, decisionOption, userWords, grantId,
                    answeredByName, expiresAt, createdAt, answeredAt, consumedAt, remainingSeconds,
                    conversationTitle, queueIndex, queueTotal, queuePreview, null);
        }
    }

    /** 创建决策单请求（工具 planRound 调用） */
    public record CreateDecisionRequest(
            String conversationId,
            String sessionId,
            String toolCallId,
            String agentCode,
            long ownerUserId,
            String type,
            String toolName,
            String scopeKey,
            String scopeDesc,
            boolean allowSessionGrant,
            String actionLabel,
            String targetSummary,
            List<KeyParamItem> keyParams,
            String actionSummary,
            Object rawPayload,
            String payloadHash,
            Integer ttlMinutes
    ) {
    }

    /** 决策单数据库实体（映射 agentx_decision） */
    public record DecisionEntity(
            String decisionId,
            String conversationId,
            String sessionId,
            String toolCallId,
            String agentCode,
            long ownerUserId,
            String type,
            String status,
            int revision,
            String toolName,
            String scopeKey,
            String actionSummary,
            String payloadJson,
            String payloadHash,
            String optionsJson,
            String decisionOption,
            String answerJson,
            String userWords,
            String grantId,
            Long answeredBy,
            String answeredByName,
            String clientIp,
            String commandId,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            LocalDateTime answeredAt,
            LocalDateTime consumedAt,
            String resolutionMode
    ) {
        public DecisionEntity(
                String decisionId, String conversationId, String sessionId, String toolCallId,
                String agentCode, long ownerUserId, String type, String status, int revision,
                String toolName, String scopeKey, String actionSummary, String payloadJson,
                String payloadHash, String optionsJson, String decisionOption, String answerJson,
                String userWords, String grantId, Long answeredBy, String answeredByName,
                String clientIp, String commandId, LocalDateTime expiresAt, LocalDateTime createdAt,
                LocalDateTime answeredAt, LocalDateTime consumedAt) {
            this(decisionId, conversationId, sessionId, toolCallId, agentCode, ownerUserId, type, status,
                    revision, toolName, scopeKey, actionSummary, payloadJson, payloadHash, optionsJson,
                    decisionOption, answerJson, userWords, grantId, answeredBy, answeredByName, clientIp,
                    commandId, expiresAt, createdAt, answeredAt, consumedAt, null);
        }
    }

    /** 会话级同类授权实体（映射 agentx_decision_grant） */
    public record DecisionGrantEntity(
            String grantId,
            long userId,
            String conversationId,
            String sourceDecisionId,
            String toolName,
            String scopeKey,
            String scopeDesc,
            String status,
            int maxUses,
            int usedCount,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            LocalDateTime revokedAt
    ) {
    }

    /** 创建结果：若命中会话级同类放行，autoApproved=true 且携带 grant */
    public record CreateResult(
            DecisionEntity entity,
            HitlCardPayload cardPayload,
            boolean autoApproved,
            DecisionGrantEntity grant
    ) {
    }

    /** 审批请求体 POST /api/hitl/decisions/{id}/decide */
    public record DecideRequest(
            String option,
            Integer revision,
            String commandId,
            String userWords,
            Map<String, Object> answerJson
    ) {
    }

    /** 审批响应体 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecideResponse(
            String decisionId,
            String status,
            int revision,
            String mode,
            String grantId,
            String decisionOption,
            String userWords,
            String answeredByName,
            String answeredAt,
            String resumePrompt,
            HitlCardPayload decision
    ) {
    }

    /** 消费结果枚举 */
    public enum ConsumeStatus {
        OK,
        NOT_FOUND,
        EXPIRED,
        ALREADY_CONSUMED,
        NOT_APPROVED,
        USER_MISMATCH,
        ACTION_MISMATCH,
        PAYLOAD_MISMATCH
    }

    public record ConsumeOutcome(
            boolean ok,
            ConsumeStatus status,
            DecisionEntity entity,
            String message
    ) {
    }
}

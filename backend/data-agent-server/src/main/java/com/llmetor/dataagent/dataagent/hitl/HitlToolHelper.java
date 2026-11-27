package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.UnknownOutcomeException;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import com.llmetor.miniagentx.agent.ToolExecutionContext;
import com.llmetor.miniagentx.interrupt.HitlParkException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 写操作工具接入 HITL 的统一辅助组件：
 * <ul>
 *   <li>判断工具是否启用 HITL（支持灰度开关）；</li>
 *   <li>序列化/反序列化持久化执行计划（重启可恢复，不依赖内存 PlanCache）；</li>
 *   <li>统一处理 verifyAndConsume 失败提示文案；</li>
 *   <li>三期 HITL：统一截获 {@link UnknownOutcomeException} 拉起 {@code VERIFY_OUTCOME} 核对型 HITL。</li>
 * </ul>
 */
@Component
public class HitlToolHelper {

    private final DecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final ActionAuditLogger auditLogger;
    private final String platformUrl;

    @Autowired
    public HitlToolHelper(
            DecisionService decisionService,
            ObjectMapper objectMapper,
            @Autowired(required = false) ActionAuditLogger auditLogger,
            @Value("${data-agent.action.platform-url:http://192.168.2.77:3000}") String platformUrl) {
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.auditLogger = auditLogger;
        this.platformUrl = platformUrl;
    }

    /** 审批类工具持久化计划载体（存入 agentx_decision.payload_json） */
    public record ReviewStoredPlan(
            String action,
            String taskId,
            String ledgerId,
            String requestBodyJson,
            String outcomeNote) {
    }

    public boolean isEnabled(String toolName) {
        return decisionService != null && decisionService.isHitlEnabled(toolName);
    }

    public boolean isDecisionId(String token) {
        return StringUtils.hasText(token) && token.trim().startsWith("dec_");
    }

    public String payloadHash(Object payload) {
        return decisionService.payloadHash(payload);
    }

    public CreateResult createDecision(CreateDecisionRequest req) {
        return decisionService.createOrAutoApprove(req);
    }

    public <T> T loadStoredPlan(String decisionId, Class<T> clazz) {
        DecisionEntity entity = decisionService.findById(decisionId);
        if (entity == null || !StringUtils.hasText(entity.payloadJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.payloadJson(), clazz);
        } catch (Exception e) {
            return null;
        }
    }

    public ObjectNode parseObjectNode(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public ConsumeOutcome verifyAndConsume(String decisionId, long uid, String toolName, String hash) {
        return decisionService.verifyAndConsume(decisionId, uid, toolName, hash);
    }

    /**
     * 二期 HITL 统一原地等待与恢复闭环：
     * <ul>
     *   <li>若为慢路径恢复且决策单已 APPROVED：直接原地执行；</li>
     *   <li>若为慢路径恢复且决策单已 REJECTED：发 HITL_RESOLVED（若 REJECT_ALL 则标记截断）并返回拒绝反馈；</li>
     *   <li>否则：预落盘 PauseState 后原地 await(60s)：
     *     <ul>
     *       <li>60s 内批准：毫秒级原地执行（0 额外 LLM 开销）；</li>
     *       <li>60s 内拒绝/全部拒绝：发 HITL_RESOLVED（若 REJECT_ALL 则标记截断）并返回拒绝反馈；</li>
     *       <li>超 60s 或停机：抛出 {@link HitlParkException} 优雅挂起释放线程。</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public String awaitAndExecute(CreateResult created, String actionLabel,
                                  Function<String, String> approvedExecutor) {
        DecisionEntity entity = created.entity();
        String decId = entity.decisionId();

        if (created.autoApproved() || STATUS_APPROVED.equals(entity.status())) {
            return approvedExecutor.apply(decId);
        }
        if (STATUS_REJECTED.equals(entity.status())) {
            if (OPTION_REJECT_ALL.equals(entity.decisionOption())) {
                ToolExecutionContext.markRejectAll();
            }
            decisionService.emitResolved(entity);
            return formatRejectedFeedback(actionLabel, entity.userWords());
        }
        if (STATUS_EXPIRED.equals(entity.status()) || STATUS_SUPERSEDED.equals(entity.status())) {
            decisionService.emitWithdrawn(entity);
            return "⏹️ 该审批单已过期或被新对话取代（未执行）。";
        }

        DecisionAwaitRegistry.AwaitResult res = decisionService.awaitDecision(decId);
        return switch (res.state()) {
            case APPROVED -> approvedExecutor.apply(decId);
            case REJECT_ALL -> {
                ToolExecutionContext.markRejectAll();
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                yield formatRejectedFeedback(actionLabel, res.userWords());
            }
            case REJECTED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                yield formatRejectedFeedback(actionLabel, res.userWords());
            }
            case PARKED -> throw new HitlParkException(decId, "等待人工审批超时，已转入后台挂起");
            case EXPIRED, SUPERSEDED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitWithdrawn(updated != null ? updated : entity);
                yield "⏹️ 该审批单已过期或被新对话取代（未执行）。";
            }
            default -> "⏹️ 该审批单状态已变更（未执行）。";
        };
    }

    private static String formatRejectedFeedback(String actionLabel, String userWords) {
        String reason = StringUtils.hasText(userWords) ? userWords.trim() : "未说明具体原因";
        return "❌ 用户已在审批卡片上【拒绝】执行（" + actionLabel + "），拒绝理由：「" + reason
                + "」。请不要调用写操作执行，并结合该拒绝理由向用户反馈调整建议或下一步方案。";
    }

    /**
     * 三期 HITL：统一截获写请求 {@link UnknownOutcomeException}，拉起 {@code VERIFY_OUTCOME} 核对型 HITL。
     * 严禁自动重试！仅当用户去主平台核对后明确选择「确认未生效，重新执行」时才安全重发一次。
     */
    public <T> String executeWithVerifyOutcome(
            String toolName, String actionLabel, String bizId, Long uid, String conversationId,
            Supplier<T> writeCall, Function<T, String> onSuccess) {
        // 慢路径恢复：若当前正在恢复一张 VERIFY_OUTCOME 决策单，直接按其终态分流，不重发首轮请求
        String resumeDecId = ToolExecutionContext.currentResumeDecisionId();
        if (StringUtils.hasText(resumeDecId)) {
            DecisionEntity resumeEntity = decisionService.findById(resumeDecId);
            if (resumeEntity != null && TYPE_VERIFY_OUTCOME.equals(resumeEntity.type())) {
                return handleVerifyOutcomeState(resumeEntity, toolName, actionLabel, bizId, uid, conversationId,
                        writeCall, onSuccess);
            }
        }
        try {
            T result = writeCall.get();
            return onSuccess.apply(result);
        } catch (UnknownOutcomeException uoe) {
            Map<String, Object> rawPayload = new LinkedHashMap<>();
            rawPayload.put("bizId", bizId);
            rawPayload.put("actionLabel", actionLabel);
            rawPayload.put("requestTime", uoe.getRequestTime());
            rawPayload.put("errorSummary", uoe.getErrorSummary());
            rawPayload.put("platformUrl", platformUrl);
            rawPayload.put("method", uoe.getMethod());
            rawPayload.put("path", uoe.getPath());

            List<KeyParamItem> kps = cleanKeyParams(List.of(
                    kp("业务单号", bizId),
                    kp("请求时间", uoe.getRequestTime()),
                    kp("错误摘要", uoe.getErrorSummary())
            ));
            String hash = decisionService.payloadHash(rawPayload);
            CreateResult created = decisionService.createOrAutoApprove(new CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid != null ? uid : 0L,
                    TYPE_VERIFY_OUTCOME, toolName, null, null, false,
                    actionLabel, bizId, kps,
                    "「" + actionLabel + "」网络超时结果未知，请前往主平台核对（" + bizId + "）",
                    rawPayload, hash, null));

            DecisionEntity entity = created.entity();
            String decId = entity.decisionId();
            DecisionAwaitRegistry.AwaitResult res = decisionService.awaitDecision(decId, true);
            return switch (res.state()) {
                case CONFIRMED_SUCCEEDED -> finalizeVerifySucceeded(entity, toolName, bizId, uid, conversationId);
                case CONFIRMED_NOT_APPLIED -> finalizeVerifyRetry(entity, toolName, bizId, uid, conversationId, writeCall, onSuccess);
                case ABORTED -> finalizeVerifyAborted(entity, toolName, bizId, uid, conversationId);
                case PARKED -> throw new HitlParkException(decId, "等待人工核对主平台结果超时，已转入后台挂起");
                case EXPIRED, SUPERSEDED -> {
                    DecisionEntity updated = decisionService.findById(decId);
                    decisionService.emitWithdrawn(updated != null ? updated : entity);
                    yield "⏹️ 该核对单已过期或被新对话取代（未重发请求）。";
                }
                default -> "⏹️ 核对单状态已变更（未重发请求）。";
            };
        }
    }

    private <T> String handleVerifyOutcomeState(
            DecisionEntity entity, String toolName, String actionLabel, String bizId,
            Long uid, String conversationId, Supplier<T> writeCall, Function<T, String> onSuccess) {
        return switch (entity.status()) {
            case STATUS_CONFIRMED_SUCCEEDED -> finalizeVerifySucceeded(entity, toolName, bizId, uid, conversationId);
            case STATUS_CONFIRMED_NOT_APPLIED -> finalizeVerifyRetry(entity, toolName, bizId, uid, conversationId, writeCall, onSuccess);
            case STATUS_ABORTED -> finalizeVerifyAborted(entity, toolName, bizId, uid, conversationId);
            case STATUS_EXPIRED, STATUS_SUPERSEDED -> {
                decisionService.emitWithdrawn(entity);
                yield "⏹️ 该核对单已过期或被新对话取代（未重发请求）。";
            }
            default -> throw new HitlParkException(entity.decisionId(), "等待人工核对主平台结果");
        };
    }

    private String finalizeVerifySucceeded(DecisionEntity entity, String toolName, String bizId,
                                         Long uid, String conversationId) {
        DecisionEntity updated = decisionService.findById(entity.decisionId());
        decisionService.emitResolved(updated != null ? updated : entity);
        logVerifyAudit(conversationId, uid, toolName, bizId, entity.decisionId(), true, "人工核对已生效，补记成功");
        return "✅ 经您人工核对，主平台已生效（业务单号：" + bizId + "）。系统未重复发送请求，已补记成功审计，请继续后续流程。";
    }

    private <T> String finalizeVerifyRetry(DecisionEntity entity, String toolName, String bizId,
                                           Long uid, String conversationId,
                                           Supplier<T> writeCall, Function<T, String> onSuccess) {
        DecisionEntity updated = decisionService.findById(entity.decisionId());
        decisionService.emitResolved(updated != null ? updated : entity);
        logVerifyAudit(conversationId, uid, toolName, bizId, entity.decisionId(), true, "人工核对未生效，安全重发一次");
        try {
            T retryRes = writeCall.get();
            return "🔄 已根据您的人工核对结果安全重发一次：\n\n" + onSuccess.apply(retryRes);
        } catch (UnknownOutcomeException again) {
            logVerifyAudit(conversationId, uid, toolName, bizId, entity.decisionId(), false,
                    "人工核对后重发再次超时: " + again.getErrorSummary());
            return "❌ 人工核对后安全重发一次仍发生超时（" + again.getErrorSummary()
                    + "）。为避免重复提交，请前往主平台确认后手动处理。";
        }
    }

    private String finalizeVerifyAborted(DecisionEntity entity, String toolName, String bizId,
                                       Long uid, String conversationId) {
        DecisionEntity updated = decisionService.findById(entity.decisionId());
        decisionService.emitResolved(updated != null ? updated : entity);
        logVerifyAudit(conversationId, uid, toolName, bizId, entity.decisionId(), false, "人工核对后选择放弃");
        return "⏹️ 您已放弃该写操作（业务单号：" + bizId + "），系统未重发请求，本次操作已中止。";
    }

    private void logVerifyAudit(String conversationId, Long uid, String toolName, String bizId,
                                String decisionId, boolean success, String detail) {
        if (auditLogger == null) {
            return;
        }
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId)
                .userId(uid)
                .tool(toolName)
                .action("verify-outcome")
                .businessObject(bizId)
                .decisionId(decisionId)
                .success(success)
                .detail(detail)
                .build());
    }

    public static String formatPlanHint(CreateResult created, String summaryLine, String bizKeyHint) {
        HitlCardPayload card = created.cardPayload();
        StringBuilder sb = new StringBuilder();
        sb.append("⏳ 已生成结构化审批单 `").append(card.decisionId()).append("`（**尚未执行**，等待用户在界面点击确认）：\n");
        sb.append("- ").append(summaryLine).append("\n");
        if (StringUtils.hasText(bizKeyHint)) {
            sb.append("- 关联标识：").append(bizKeyHint).append("\n");
        }
        sb.append("请简短提示用户核对界面弹出的审批卡片，点击【仅本次允许】")
                .append(card.allowSessionGrant() ? "、【本会话同类放行】" : "")
                .append("或【拒绝】（可填写理由）；**切勿要求用户手打任何确认暗号或令牌**。");
        return sb.toString();
    }

    public static String formatConsumeError(ConsumeOutcome outcome, String toolName) {
        return switch (outcome.status()) {
            case ALREADY_CONSUMED -> "❌ 该审批单已执行过（一次性生效），请勿重复提交。";
            case EXPIRED -> "❌ 该审批单已过期失效（超过有效期），请重新发起 " + toolName + " 生成新的审批单。";
            case PAYLOAD_MISMATCH -> "❌ 业务数据或操作参数已发生变化（指纹不匹配），原审批单已拦截执行。请重新核对现状后发起。";
            case NOT_APPROVED -> "❌ 该审批单尚未获得批准（当前状态："
                    + (outcome.entity() != null ? outcome.entity().status() : "未知")
                    + "），请先在界面点击批准后再执行。";
            case USER_MISMATCH, ACTION_MISMATCH -> "❌ 审批单校验失败（归属用户或动作类型不匹配），已拒绝执行。";
            default -> "❌ 审批单不存在或已失效，请重新发起 " + toolName + "。";
        };
    }

    public static KeyParamItem kp(String label, String value) {
        return new KeyParamItem(label, value == null || value.isBlank() ? "—" : value);
    }

    public static List<KeyParamItem> cleanKeyParams(List<KeyParamItem> items) {
        return items.stream().filter(i -> i != null && StringUtils.hasText(i.label())).toList();
    }
}

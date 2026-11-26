package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import com.llmetor.miniagentx.agent.ToolExecutionContext;
import com.llmetor.miniagentx.interrupt.HitlParkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 写操作工具接入一期 HITL 的统一辅助组件：
 * <ul>
 *   <li>判断工具是否启用 HITL（支持灰度开关）；</li>
 *   <li>序列化/反序列化持久化执行计划（重启可恢复，不依赖内存 PlanCache）；</li>
 *   <li>统一处理 verifyAndConsume 失败提示文案。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class HitlToolHelper {

    private final DecisionService decisionService;
    private final ObjectMapper objectMapper;

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
        };
    }

    private static String formatRejectedFeedback(String actionLabel, String userWords) {
        String reason = StringUtils.hasText(userWords) ? userWords.trim() : "未说明具体原因";
        return "❌ 用户已在审批卡片上【拒绝】执行（" + actionLabel + "），拒绝理由：「" + reason
                + "」。请不要调用写操作执行，并结合该拒绝理由向用户反馈调整建议或下一步方案。";
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

package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

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

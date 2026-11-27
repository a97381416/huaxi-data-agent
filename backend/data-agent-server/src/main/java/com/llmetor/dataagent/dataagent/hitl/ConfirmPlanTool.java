package com.llmetor.dataagent.dataagent.hitl;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import com.llmetor.miniagentx.interrupt.HitlParkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;

/**
 * 三期 HITL：计划确认型内置工具（confirm_plan）。
 *
 * <p>复杂多步骤任务先输出结构化《执行计划清单》，阻塞等待用户拍板：
 * <ul>
 *   <li>批准并开始执行（APPROVE_PLAN）→ 开始按计划执行；后续若含 L3 高危写操作仍需单独授权；</li>
 *   <li>打回修改（REVISE_PLAN）→ 携带用户修改意见返回 LLM 调整计划后重新确认；</li>
 *   <li>超 60s 无操作 → 优雅挂起（HitlParkException），支持慢路径恢复。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmPlanTool {

    public static final String TOOL_NAME = "confirm_plan";

    private final DecisionService decisionService;

    @Tool(name = TOOL_NAME,
            description = "在执行复杂多步骤任务（如批量校对、多项目处理、长链路操作）前，先向用户展示结构化的《执行计划清单》并等待用户确认。" +
                    "用户可点击【批准并开始执行】或【打回修改】并给出修改意见。")
    public String confirmPlan(
            @ToolParam(description = "执行计划标题，如“5个项目填报资料批量校对计划”", required = true) String title,
            @ToolParam(description = "Markdown 格式的执行计划步骤清单", required = true) String planMarkdown,
            @ToolParam(description = "步骤总数量（可选）", required = false) Integer stepCount,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，无法发起计划确认。";
        }
        if (!StringUtils.hasText(planMarkdown)) {
            return "❌ 执行计划内容 planMarkdown 不能为空。";
        }
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "任务执行计划";

        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("title", safeTitle);
        rawPayload.put("planMarkdown", planMarkdown.trim());
        if (stepCount != null && stepCount > 0) {
            rawPayload.put("stepCount", stepCount);
        }

        String hash = decisionService.payloadHash(rawPayload);
        CreateResult created = decisionService.createOrAutoApprove(new CreateDecisionRequest(
                conversationId, conversationId, null, null, uid,
                TYPE_PLAN_CONFIRM, TOOL_NAME, null, null, false,
                "执行计划确认", safeTitle,
                stepCount != null && stepCount > 0
                        ? List.of(HitlToolHelper.kp("计划步骤", stepCount + " 步"))
                        : List.of(),
                safeTitle, rawPayload, hash, null));

        DecisionEntity entity = created.entity();
        String decId = entity.decisionId();

        // 慢路径恢复时，决策单已处于终态则直接消费返回
        if (STATUS_APPROVED.equals(entity.status())) {
            decisionService.emitResolved(entity);
            return "✅ 用户已批准执行计划「" + safeTitle + "」，请按计划开始逐步执行。若后续涉及高危写操作仍需单独审批。";
        }
        if (STATUS_REVISED.equals(entity.status())) {
            decisionService.emitResolved(entity);
            String words = StringUtils.hasText(entity.userWords()) ? entity.userWords().trim() : "请调整计划";
            return "🔄 用户打回了执行计划「" + safeTitle + "」，修改意见：「" + words
                    + "」。请按用户意见调整计划后，再次调用 confirm_plan 重新确认。";
        }
        if (STATUS_EXPIRED.equals(entity.status()) || STATUS_SUPERSEDED.equals(entity.status())) {
            decisionService.emitWithdrawn(entity);
            return "⏹️ 该执行计划确认单已过期或被新对话取代。";
        }

        DecisionAwaitRegistry.AwaitResult res = decisionService.awaitDecision(decId, true);
        return switch (res.state()) {
            case APPROVED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                yield "✅ 用户已批准执行计划「" + safeTitle + "」，请按计划开始逐步执行。若后续涉及高危写操作仍需单独审批。";
            }
            case REVISED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitResolved(updated != null ? updated : entity);
                String words = StringUtils.hasText(res.userWords()) ? res.userWords().trim() : "请调整计划";
                yield "🔄 用户打回了执行计划「" + safeTitle + "」，修改意见：「" + words
                        + "」。请按用户意见调整计划后，再次调用 confirm_plan 重新确认。";
            }
            case PARKED -> throw new HitlParkException(decId, "等待人工确认执行计划超时，已转入后台挂起");
            case EXPIRED, SUPERSEDED -> {
                DecisionEntity updated = decisionService.findById(decId);
                decisionService.emitWithdrawn(updated != null ? updated : entity);
                yield "⏹️ 该执行计划确认单已过期或被新对话取代。";
            }
            default -> "⏹️ 该执行计划确认单状态已变更。";
        };
    }

    private static Long parseUserId(String userId) {
        if (StringUtils.hasText(userId)) {
            try {
                return Long.parseLong(userId.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return HuaxiAuthContext.getCurrentUserIdOrNull();
    }
}

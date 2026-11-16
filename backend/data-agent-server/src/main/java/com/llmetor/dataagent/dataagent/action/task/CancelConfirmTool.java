package com.llmetor.dataagent.dataagent.action.task;

import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.review.ReviewPlanCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高危动作取消工具（M-E1）：作废未执行的确认令牌（协议附录 B——
 * [取消] 按钮 = 注入「取消 ct_xxx」→ 服务端即时作废，防过期窗口内被复用）。
 *
 * <p>通用工具：对当前及后续所有 W2 工具（resubmitLedger / submitLedgerForReview /
 * approveTask / rejectTask / partialApproveLedger / deleteProject / closeWarning…）
 * 的令牌生效；同时清理关联的执行计划缓存。
 * 取消不产生任何业务副作用。
 */
@Slf4j
@Component
public class CancelConfirmTool {

    private final ConfirmTokenService confirmTokenService;
    private final ActionPlanCache planCache;
    private final ReviewPlanCache reviewPlanCache;
    private final ActionAuditLogger auditLogger;

    public CancelConfirmTool(ConfirmTokenService confirmTokenService,
                             ActionPlanCache planCache,
                             ReviewPlanCache reviewPlanCache,
                             ActionAuditLogger auditLogger) {
        this.confirmTokenService = confirmTokenService;
        this.planCache = planCache;
        this.reviewPlanCache = reviewPlanCache;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "cancelConfirm",
            description = "作废一个尚未执行的高危操作确认令牌（用户点计划卡片[取消]按钮、或明确说「取消/不执行」时调用）。" +
                    "参数为执行计划里的 confirmToken（ct_ 开头）。取消后该计划立即失效，不产生任何变更；" +
                    "用户想再次执行需重新生成计划并确认。")
    public String cancelConfirm(
            @ToolParam(description = "要作废的确认令牌（来自执行计划，ct_ 开头）", required = true)
            String confirmToken,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = TaskIds.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        String ct = confirmToken == null ? "" : confirmToken.trim();
        if (ct.isBlank()) {
            return "❌ 缺少确认令牌（confirmToken），无法取消。";
        }
        boolean revoked = confirmTokenService.revoke(ct);
        planCache.remove(ct);
        reviewPlanCache.remove(ct);

        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("cancelConfirm").action("cancel-confirm")
                .businessObject("confirm-token").confirmToken(ct)
                .success(true)
                .detail("existed=" + revoked)
                .build());

        return revoked
                ? "✅ 已取消该操作，确认令牌已作废，未产生任何变更。用户想再次执行时需重新生成执行计划并确认。"
                : "ℹ️ 该确认令牌不存在或已失效（可能已过期/已执行/已取消过），无需重复取消。";
    }
}

package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels;
import com.llmetor.dataagent.dataagent.hitl.HitlToolHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核驳回工具（M-E3，W2 写·高危：两段式确认令牌，协议见计划附录 B）。
 *
 * <p><b>首轮</b>（无 confirmToken）：归属→状态守卫→**驳回意见必填**（对齐 PC RejectDialog
 * 前端强制；引擎虽不强制，但无意见的驳回会让申报人无从改起）→ GET /task-center/task/approve-chain
 * → 驳回目标三选一解析（originator=链首发起人[默认] / parent=倒数第二上级节点 /
 * specified=按节点名匹配，歧义返回候选清单）→ 签发确认令牌 → 计划快照入 {@link ReviewPlanCache}
 * → 纯对话文本返回执行计划（零写副作用）。
 *
 * <p><b>二轮</b>（只传 taskId + confirmToken）：重查任务/台账现状（漂移→作废）→
 * verifyAndConsume（一次性；过期→同计划重签令牌一轮闭环）→ POST /task-center/task/reject
 * → 审计 → 回报（台账置驳回、申报人收到重提任务、意见随任务送达）。
 */
@Slf4j
@Component
public class RejectTaskTool {

    static final String ACTION = "rejectTask";

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ConfirmTokenService confirmTokenService;
    private final ReviewPlanCache planCache;
    private final ActionAuditLogger auditLogger;
    private final HitlToolHelper hitlHelper;

    public RejectTaskTool(RestActionClient restActionClient,
                          ReviewSupport support,
                          ConfirmTokenService confirmTokenService,
                          ReviewPlanCache planCache,
                          ActionAuditLogger auditLogger) {
        this(restActionClient, support, confirmTokenService, planCache, auditLogger, null);
    }

    @Autowired
    public RejectTaskTool(RestActionClient restActionClient,
                          ReviewSupport support,
                          ConfirmTokenService confirmTokenService,
                          ReviewPlanCache planCache,
                          ActionAuditLogger auditLogger,
                          HitlToolHelper hitlHelper) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.confirmTokenService = confirmTokenService;
        this.planCache = planCache;
        this.auditLogger = auditLogger;
        this.hitlHelper = hitlHelper;
    }

    @Tool(name = "rejectTask",
            description = "审核【驳回】（高危：回退审批流，必须两段式确认）。" +
                    "① 首轮：传 taskId + opinion（驳回意见，必填——说清哪里不行、要改什么）；" +
                    "target 驳回目标：originator=退回发起人（默认）/ parent=退回上级审核节点 / " +
                    "specified=退回指定节点（配合 targetNodeName=节点名，候选清单见 getReviewDetail 审批链）。" +
                    "【不要传 confirmToken】→ 返回执行计划与确认令牌（不执行）。" +
                    "② 二轮：用户明确同意后，只传 taskId + confirmToken → 真正执行驳回。" +
                    "用户取消时改调 cancelConfirm(confirmToken)。")
    public String rejectTask(
            @ToolParam(description = "审核任务ID（来自 listReviewTasks，字符串原样传入）", required = true)
            String taskId,
            @ToolParam(description = "驳回意见（必填：哪里不行、申报人要改什么）", required = true)
            String opinion,
            @ToolParam(description = "驳回目标：originator=发起人（默认）/ parent=上级节点 / specified=指定节点", required = false)
            String target,
            @ToolParam(description = "target=specified 时的目标节点名（来自 getReviewDetail 审批链）", required = false)
            String targetNodeName,
            @ToolParam(description = "确认令牌（二轮执行时传入；首轮严禁传，由服务端签发）", required = false)
            String confirmToken,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = ReviewSupport.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = ReviewSupport.parseLong(tenantId);

        try {
            if (confirmToken != null && !confirmToken.isBlank()) {
                return executeRound(uid, taskId.trim(), confirmToken.trim(), token, tenant, conversationId);
            }
            return planRound(uid, taskId, opinion, target, targetNodeName, token, tenant, conversationId);
        } catch (ReviewSupport.TaskFlowException e) {
            audit(uid, conversationId, "reject", taskId, null, false, e.getMessage());
            return e.getMessage();
        }
    }

    // ── 首轮：出计划（零写副作用）──────────────────────────────────────────────

    private String planRound(Long uid, String taskId, String opinion, String target, String targetNodeName,
                             String token, Long tenant, String conversationId) {
        ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
        support.requireApprovalTodoTask(task);
        if (opinion == null || opinion.isBlank()) {
            return "❌ 驳回意见必填（申报人要靠它知道改什么）。请向用户问清「哪里不行、要改什么」，"
                    + "整理成一句通顺表述回显确认后再发起首轮。";
        }

        // 驳回目标解析（链上 nodeId；歧义/未命中抛候选清单）
        RestActionClient.ActionResult cr = restActionClient.get(
                "/task-center/task/approve-chain",
                ReviewSupport.query("taskId", task.taskId()), token, tenant);
        support.requireOk(cr, "查询审批链");
        ReviewSupport.RejectTarget resolved = support.resolveRejectTarget(
                target, targetNodeName, cr.data(), task.initiatorName());

        // 组装最终请求体（doc 任务附带业务变量，便于引擎留痕与后续定位）
        ObjectNode body = support.mapper().createObjectNode();
        body.put("taskId", Long.parseLong(task.taskId()));
        body.put("opinion", opinion.trim());
        body.put("targetNodeId", Long.parseLong(resolved.targetNodeId()));
        if (task.docLedger()) {
            ObjectNode vars = body.putObject("variables");
            String projectId = task.ledgerHeader().path("projectId").asText("");
            String phaseId = task.ledgerHeader().path("phaseId").asText("");
            if (!projectId.isEmpty()) {
                vars.put("projectId", projectId);
            }
            if (!phaseId.isEmpty()) {
                vars.put("phaseId", phaseId);
            }
            if (!task.ledgerId().isEmpty()) {
                vars.put("ledgerId", task.ledgerId());
            }
        }

        String outcomeNote = "驳回至 " + resolved.targetLabel()
                + (task.docLedger() ? "；台账置「已驳回」，驳回意见随重提任务送达申报人"
                : "；该节点之后的审批链被取消，目标处理人收到重新处理任务");
        String canonical = canonicalJson(body);
        String hash = confirmTokenService.payloadHash(List.of(ACTION, task.taskId(), canonical));
        if (hitlHelper != null && hitlHelper.isEnabled(ACTION)) {
            String docNum = task.docLedger() ? task.ledgerHeader().path("docNumber").asText("") : "";
            String targetSummary = task.title() + (docNum.isBlank() ? "" : "（" + docNum + "）");
            List<DecisionModels.KeyParamItem> kps = List.of(
                    HitlToolHelper.kp("任务ID", task.taskId()),
                    HitlToolHelper.kp("退回到", resolved.targetLabel()),
                    HitlToolHelper.kp("驳回意见", opinion.trim()),
                    HitlToolHelper.kp("影响", outcomeNote)
            );
            String actionSummary = "审核驳回「" + task.title() + "」退回至" + resolved.targetLabel();
            HitlToolHelper.ReviewStoredPlan stored = new HitlToolHelper.ReviewStoredPlan(
                    ACTION, task.taskId(), task.ledgerId(), canonical, outcomeNote);
            DecisionModels.CreateResult created = hitlHelper.createDecision(new DecisionModels.CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid,
                    DecisionModels.TYPE_PERMISSION, ACTION, null, null, false,
                    "审核驳回 (" + ACTION + ")", targetSummary, kps, actionSummary,
                    stored, hash, null));
            String decId = created.entity().decisionId();
            audit(uid, conversationId, "reject-plan", task, decId, true,
                    "hitl=true, target=" + (target == null || target.isBlank() ? "originator" : target));
            return HitlToolHelper.formatPlanHint(created, actionSummary,
                    "taskId=" + task.taskId() + ", confirmToken=" + decId);
        }

        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, ACTION, hash);
        planCache.put(issued.confirmToken(), ACTION, task.taskId(), task.ledgerId(), body, outcomeNote);

        audit(uid, conversationId, "reject-plan", task, issued.confirmToken(), true,
                "target=" + (target == null || target.isBlank() ? "originator" : target));

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 审核驳回执行计划（**尚未执行**，等待用户确认）\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        if (task.docLedger()) {
            sb.append("- 台账：").append(task.ledgerHeader().path("docNumber").asText("")).append('\n');
        }
        sb.append("- 动作：**驳回**｜退回到：").append(resolved.targetLabel()).append('\n');
        sb.append("- 驳回意见：「").append(opinion.trim()).append("」\n");
        sb.append("- 影响：").append(outcomeNote)
                .append("（驳回后实例保持进行中；目标节点之后的审批记录被截断）\n");
        sb.append("确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效，一次性）\n");
        sb.append("请把以上计划完整展示给用户并等待明确同意；同意后只需传 taskId + confirmToken 再次调用本工具执行。");
        return sb.toString();
    }

    // ── 二轮：携带令牌执行 ─────────────────────────────────────────────────────

    private String executeRound(Long uid, String taskId, String confirmToken,
                                String token, Long tenant, String conversationId) {
        if (hitlHelper != null && hitlHelper.isDecisionId(confirmToken)) {
            HitlToolHelper.ReviewStoredPlan stored = hitlHelper.loadStoredPlan(
                    confirmToken, HitlToolHelper.ReviewStoredPlan.class);
            ObjectNode reqBody = stored != null ? hitlHelper.parseObjectNode(stored.requestBodyJson()) : null;
            if (stored == null || reqBody == null || !ACTION.equals(stored.action()) || !taskId.equals(stored.taskId())) {
                return "❌ 审批单无效或与任务不匹配（" + confirmToken + "）。请重新发起 rejectTask。";
            }
            ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
            try {
                support.requireApprovalTodoTask(task);
                support.requireLedgerInReview(task);
            } catch (ReviewSupport.TaskFlowException e) {
                return "❌ 任务现状已发生变化，原审批单失效（未执行）。原因：" + e.getMessage();
            }
            String hash = confirmTokenService.payloadHash(List.of(ACTION, taskId, stored.requestBodyJson()));
            DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(confirmToken, uid, ACTION, hash);
            if (!consume.ok()) {
                return HitlToolHelper.formatConsumeError(consume, ACTION);
            }
            RestActionClient.ActionResult r = restActionClient.post(
                    "/task-center/task/reject", reqBody, token, tenant);
            if (!r.success()) {
                audit(uid, conversationId, "reject", task, confirmToken, false, r.msg());
                return "❌ 审核驳回执行失败：" + r.msg();
            }
            audit(uid, conversationId, "reject", task, confirmToken, true, "executed");
            return "✅ 审核驳回完成\n- 任务 " + task.title() + "：已办结（驳回）\n- 影响：" + stored.outcomeNote() + "\n";
        }

        ReviewPlanCache.Plan plan = planCache.get(confirmToken);
        if (plan == null || !ACTION.equals(plan.action()) || !taskId.equals(plan.taskId())) {
            return "❌ 确认令牌无效（从未签发/已过期/已执行/任务不符/**已被更新的计划取代**）。"
                    + "请用最新计划里的确认令牌执行；没有最新计划就重新发起 rejectTask 首轮调用。";
        }

        // 现状守卫：任务仍在我待办（被人先驳/先过 → 作废）
        ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
        try {
            support.requireApprovalTodoTask(task);
            support.requireLedgerInReview(task);
        } catch (ReviewSupport.TaskFlowException e) {
            planCache.remove(confirmToken);
            confirmTokenService.revoke(confirmToken);
            return "❌ 任务现状已发生变化，原计划失效（未执行）。原因：" + e.getMessage()
                    + "\n请重新查看详情（getReviewDetail）后再次发起。";
        }

        String hash = confirmTokenService.payloadHash(List.of(ACTION, taskId, plan.requestBodyJson()));
        ConfirmTokenService.ConsumeResult verify = confirmTokenService.verifyAndConsume(
                confirmToken, uid, ACTION, hash);
        switch (verify) {
            case OK -> {
                // 执行（唯一路径）
            }
            case EXPIRED, PAYLOAD_MISMATCH -> {
                planCache.remove(confirmToken);
                return reissuePlan(uid, task, plan, token, tenant, conversationId,
                        verify == ConfirmTokenService.ConsumeResult.EXPIRED
                                ? "原确认令牌已过期（5 分钟有效期）"
                                : "原确认令牌校验失败");
            }
            case ALREADY_USED -> {
                return "❌ 该计划已执行过（令牌一次性）。请勿重复提交；可用 listReviewTasks 查看当前状态。";
            }
            default -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌校验失败（" + verify + "），已作废。请重新发起 rejectTask 首轮调用。";
            }
        }

        RestActionClient.ActionResult r = restActionClient.post(
                "/task-center/task/reject", plan.requestBody(), token, tenant);
        if (!r.success()) {
            planCache.remove(confirmToken);
            audit(uid, conversationId, "reject", task, confirmToken, false, r.msg());
            return "❌ 审核驳回执行失败：" + r.msg() + "\n（计划已作废；如需重试请重新发起首轮。）";
        }
        planCache.remove(confirmToken);
        audit(uid, conversationId, "reject", task, confirmToken, true, "executed");

        StringBuilder sb = new StringBuilder("✅ 审核驳回完成\n");
        sb.append("- 任务 ").append(task.title()).append("：已办结（驳回）\n");
        sb.append("- 影响：").append(plan.outcomeNote()).append('\n');
        return sb.toString();
    }

    /** 同计划重签新令牌（现状守卫已通过、参数未变） */
    private String reissuePlan(Long uid, ReviewSupport.ReviewTaskRef task, ReviewPlanCache.Plan plan,
                               String token, Long tenant, String conversationId, String lead) {
        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(
                uid, ACTION, confirmTokenService.payloadHash(
                        List.of(ACTION, task.taskId(), plan.requestBodyJson())));
        planCache.put(issued.confirmToken(), ACTION, task.taskId(), task.ledgerId(),
                plan.requestBody(), plan.outcomeNote());
        audit(uid, conversationId, "reject-plan", task, issued.confirmToken(), true, "reissue");
        StringBuilder sb = new StringBuilder("⚠️ ").append(lead)
                .append("，已生成新计划（**尚未执行**）：\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        sb.append("- 影响：").append(plan.outcomeNote()).append('\n');
        sb.append("新确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效）。请把新计划完整展示给用户并等待明确同意。");
        return sb.toString();
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    /** 键排序规范化 JSON（payload 哈希计算基准，跨轮稳定） */
    private String canonicalJson(ObjectNode body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .writeValueAsString(body);
        } catch (Exception e) {
            return body.toString();
        }
    }

    private void audit(Long uid, String conversationId, String action, Object taskOrId,
                       String confirmToken, boolean success, String detail) {
        String biz;
        if (taskOrId instanceof ReviewSupport.ReviewTaskRef t) {
            biz = t.docLedger() ? t.ledgerHeader().path("docNumber").asText("") + "/task:" + t.taskId()
                    : "plain/task:" + t.taskId();
        } else {
            biz = "task:" + taskOrId;
        }
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("rejectTask").action(action)
                .businessObject(biz).confirmToken(confirmToken)
                .success(success)
                .errorMessage(success ? null : detail)
                .detail(success ? detail : null)
                .build());
    }
}

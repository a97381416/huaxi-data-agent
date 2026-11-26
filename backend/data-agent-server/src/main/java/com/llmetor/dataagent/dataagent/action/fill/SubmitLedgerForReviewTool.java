package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.FunctionPermissionChecker;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels;
import com.llmetor.dataagent.dataagent.hitl.HitlToolHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提审工具（M-E2，W2 写·高危：两段式确认令牌，协议同 M-E1 resubmitLedger——设计 §3.3/§7.8）。
 *
 * <p><b>首轮</b>（无 confirmToken）：权限 → 定位 → 草稿守卫（归属本人+状态=0）→ 闸3 硬预检
 * （D1 全状态摘要必填/已取得必传文件/待上传预设行剔除/自定义行须明确状态）→ 签发确认令牌
 * （payload 绑定 生效内容+提审配置 的 SHA-256）→ 计划快照入 {@link SubmitPlanCache} →
 * 以纯对话文本返回执行计划（无确认卡，用户在对话里口头确认）。<b>零写副作用</b>。
 *
 * <p><b>二轮</b>（ledgerId + confirmToken）：重拉草稿 → 重新预检 → 哈希比对
 * （漂移→令牌作废重出计划）→ verifyAndConsume（一次性）→
 * {@code POST /doc/ledger/submit-for-review}（needNext=true + 单节点部门会签，
 * nodeName=部门审核 / approvalStrategy=countersign / variables.opinion=提交说明）→
 * 审计 → 回报（交互全走对话，不产出确认卡）。
 *
 * <p>注意：submit-for-review 不携带 fileEntries——所有字段修改必须在提审前经
 * fillFileEntry 暂存落库；本工具不收 patch（收到即引导先暂存）。
 */
@Slf4j
@Component
public class SubmitLedgerForReviewTool {

    static final String ACTION = "submitLedgerForReview";

    /** v1 固定单节点部门会签（设计 D2，对齐业务端 SubmitApprovalDialog 默认节点） */
    static final String NODE_NAME = "部门审核";
    static final String STRATEGY = "countersign";

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final FillContextSupport contextSupport;
    private final FillPanelSupport panelSupport;
    private final ConfirmTokenService confirmTokenService;
    private final SubmitPlanCache planCache;
    private final FillNodeSelectionCache nodeSelectionCache;
    private final ListFillNodesTool listFillNodesTool;
    private final ActionAuditLogger auditLogger;
    private final HitlToolHelper hitlHelper;

    public SubmitLedgerForReviewTool(FunctionPermissionChecker permissionChecker,
                                     RestActionClient restActionClient,
                                     FillContextSupport contextSupport,
                                     FillPanelSupport panelSupport,
                                     ConfirmTokenService confirmTokenService,
                                     SubmitPlanCache planCache,
                                     FillNodeSelectionCache nodeSelectionCache,
                                     ListFillNodesTool listFillNodesTool,
                                     ActionAuditLogger auditLogger) {
        this(permissionChecker, restActionClient, contextSupport, panelSupport,
                confirmTokenService, planCache, nodeSelectionCache, listFillNodesTool, auditLogger, null);
    }

    @Autowired
    public SubmitLedgerForReviewTool(FunctionPermissionChecker permissionChecker,
                                     RestActionClient restActionClient,
                                     FillContextSupport contextSupport,
                                     FillPanelSupport panelSupport,
                                     ConfirmTokenService confirmTokenService,
                                     SubmitPlanCache planCache,
                                     FillNodeSelectionCache nodeSelectionCache,
                                     ListFillNodesTool listFillNodesTool,
                                     ActionAuditLogger auditLogger,
                                     HitlToolHelper hitlHelper) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.contextSupport = contextSupport;
        this.panelSupport = panelSupport;
        this.confirmTokenService = confirmTokenService;
        this.planCache = planCache;
        this.nodeSelectionCache = nodeSelectionCache;
        this.listFillNodesTool = listFillNodesTool;
        this.auditLogger = auditLogger;
        this.hitlHelper = hitlHelper;
    }

    @Tool(name = "submitLedgerForReview",
            description = "资料填报【提交审核】（高危：推进审批流，必须两段式确认）。" +
                    "① 首轮：传 projectIdOrName + phaseIdOrNameOrCode + submitRemark(提交说明,必填)；" +
                    "assigneeIds(审核人用户ID列表)可选——不传时服务端自动取常用审核人第1名为默认并在计划中标注。" +
                    "【不要传 confirmToken/ledgerId】→ 服务端硬预检并返回执行计划与确认令牌（不执行）。" +
                    "把计划展示给用户并等待明确同意；用户要换审核人时：说人名→调 listReviewOptions 传 userNameKeyword " +
                    "反查ID；说部门→传 deptId 查该部门人员；确定新审核人后【先 cancelConfirm(旧令牌) 再重新首轮】。" +
                    "② 二轮：用户明确同意后，只传 ledgerId + confirmToken（其余定位/配置不用再传）→ 真正执行提审。" +
                    "用户取消时改调 cancelConfirm(confirmToken)。" +
                    "注意：本工具不接收 patch——待提交内容必须已通过 fillFileEntry 暂存到草稿。")
    public String submitLedgerForReview(
            @ToolParam(description = "项目ID/名称/编码（首轮必传；二轮不用传）", required = false)
            String projectIdOrName,
            @ToolParam(description = "子阶段ID/编码/名称（首轮必传；二轮不用传）", required = false)
            String phaseIdOrNameOrCode,
            @ToolParam(description = "提交说明（首轮必填，将作为流程变量 opinion 展示给审核人）", required = false)
            String submitRemark,
            @ToolParam(description = "审核人用户ID列表（首轮可选：不传=自动取常用审核人第1名为默认；" +
                    "v1 单节点部门会签=所选人均须通过）", required = false)
            List<String> assigneeIds,
            @ToolParam(description = "审核人姓名列表（可选，与 assigneeIds 顺序对应，仅展示用）", required = false)
            List<String> assigneeNames,
            @ToolParam(description = "确认令牌（二轮执行时传入；首轮严禁传，由服务端签发）", required = false)
            String confirmToken,
            @ToolParam(description = "台账ID（二轮执行时传入，字符串原样；首轮不用传）", required = false)
            String ledgerId,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = FillContextSupport.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = FillContextSupport.parseLong(tenantId);

        try {
            if (confirmToken != null && !confirmToken.isBlank()) {
                if (ledgerId == null || ledgerId.isBlank()) {
                    return "❌ 二轮执行缺少 ledgerId（首轮计划文本里有），无法定位台账。请按首轮返回的计划参数重试。";
                }
                return executeRound(uid, ledgerId.trim(), confirmToken.trim(), token, tenant, conversationId);
            }
            return planRound(uid, projectIdOrName, phaseIdOrNameOrCode, submitRemark,
                    assigneeIds, assigneeNames, token, tenant, conversationId);
        } catch (FillContextSupport.TaskFlowException e) {
            audit(uid, conversationId, "submit", null, null, false, e.getMessage());
            return e.getMessage();
        }
    }

    // ── 首轮：预检 + 出计划（零写副作用）──────────────────────────────────────

    private String planRound(Long uid, String projectIdOrName, String phaseIdOrNameOrCode,
                             String submitRemark, List<String> assigneeIds, List<String> assigneeNames,
                             String token, Long tenant, String conversationId) {
        String denied = permissionChecker.checkPermission(uid, ListFillNodesTool.PERMISSION);
        if (denied != null) {
            return denied;
        }
        if (submitRemark == null || submitRemark.isBlank()) {
            return "❌ 缺少提交说明（submitRemark）。提审必须附提交说明，请向用户收集后重试" +
                    "（可参考：本阶段填报情况、空缺原因与补齐计划）。";
        }
        FillContextSupport.FillContext ctx = listFillNodesTool.loadContext(
                projectIdOrName, phaseIdOrNameOrCode, token, tenant);
        if (!ctx.hasDraft()) {
            return "❌ 该子阶段还没有草稿台账，没有可提交的内容。请先完成填报并 fillFileEntry 暂存后再提审。";
        }
        FillContextSupport.LedgerView ledger = ctx.ledger();
        if (ledger.status() != 0) {
            return "❌ 台账 " + ledger.docNumber() + " 当前状态为「" + FillLabels.ledgerStatus(ledger.status())
                    + "」，只有草稿状态才能提审。审批中请等待审核结果；已驳回/部分通过请走 declarant-workflow 重提闭环。";
        }

        // 闸3：硬预检（D1 口径）
        FillPanelSupport.SubmitValidation v = panelSupport.validateForSubmit(ledger.rows());
        if (!v.violations().isEmpty()) {
            StringBuilder sb = new StringBuilder("❌ 提审预检未通过，不发起提交。缺项清单：\n");
            for (String violation : v.violations()) {
                sb.append("- ").append(violation).append('\n');
            }
            sb.append("\n请引导用户补齐（面板对应行或对话补充），fillFileEntry 暂存后重新提审；")
                    .append("确实无需填报的类别可置为空缺/不涉及并填写摘要原因。");
            return sb.toString();
        }
        if (v.toSubmit().isEmpty()) {
            return "❌ 本轮没有要提交的内容（所有预设类别仍为待上传）。请先填报至少一个类别并暂存。";
        }

        // 审核人：显式传入优先；未传 → 常用审核人第 1 名默认（其余列备选）；无推荐 → 人话引导明确选人
        List<String> reviewerIds;
        List<String> names;
        String reviewerNote;
        String candidateNote = null;
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            List<ObjectNode> recommend = contextSupport.recommendAssignees(
                    ctx.project().projectId(), ctx.phase().phaseId(), token, tenant);
            if (recommend.isEmpty()) {
                return "❌ 未指定审核人，且该子阶段暂无常用审核人。" +
                        "请调 listReviewOptions 选人：用户点名姓名传 userNameKeyword 反查，或传 deptId 查部门人员；" +
                        "确认后带 assigneeIds 重新发起首轮。";
            }
            ObjectNode top = recommend.get(0);
            reviewerIds = List.of(top.path("userId").asText());
            names = List.of(top.path("nickname").asText(""));
            reviewerNote = "默认，常用审核人第 1 名";
            StringBuilder cand = new StringBuilder();
            for (int i = 1; i < recommend.size() && i < 5; i++) {
                ObjectNode c = recommend.get(i);
                if (cand.length() > 0) {
                    cand.append("、");
                }
                cand.append(c.path("nickname").asText("")).append("(ID:").append(c.path("userId").asText()).append(')');
            }
            if (cand.length() > 0) {
                candidateNote = cand.toString();
            }
        } else {
            reviewerIds = assigneeIds;
            names = assigneeNames == null ? List.of() : assigneeNames;
            reviewerNote = null;
        }

        // 令牌绑定：生效内容 + 提审配置（参数偷换/草稿漂移 → 二轮哈希不匹配作废）
        SubmitPlanCache.Plan plan = new SubmitPlanCache.Plan(ledger.ledgerId(), ctx.project().projectId(),
                ctx.phase().phaseId(), submitRemark.trim(),
                List.copyOf(reviewerIds), List.copyOf(names), "", 0L);
        String hash = payloadHash(plan, ledger.rows());

        if (hitlHelper != null && hitlHelper.isEnabled(ACTION)) {
            String targetSummary = (ledger.projectName().isBlank() ? ctx.project().projectName() : ledger.projectName())
                    + " / " + (ledger.phaseName().isBlank() ? ctx.phase().phaseName() : ledger.phaseName())
                    + "（" + ledger.docNumber() + "）";
            String scopeKey = ACTION + ":SUBMIT:project=" + plan.projectId()
                    + ":assignees=" + String.join(",", plan.assigneeIds());
            String scopeDesc = "仅限本会话内同项目同审核人（" + displayAssignees(plan) + "）的提审";
            List<DecisionModels.KeyParamItem> kps = List.of(
                    HitlToolHelper.kp("台账单号", ledger.docNumber()),
                    HitlToolHelper.kp("审核人", displayAssignees(plan) + (reviewerNote == null ? "" : "（" + reviewerNote + "）")),
                    HitlToolHelper.kp("本轮提交", v.toSubmit().size() + " 项（跳过待上传 " + v.skippedCategories().size() + " 项）"),
                    HitlToolHelper.kp("提交说明", shortSummary(plan.submitRemark()))
            );
            String actionSummary = "提交台账审核「" + ledger.docNumber() + "」（审核人：" + displayAssignees(plan) + "）";
            DecisionModels.CreateResult created = hitlHelper.createDecision(new DecisionModels.CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid,
                    DecisionModels.TYPE_PERMISSION, ACTION, scopeKey, scopeDesc, true,
                    "提交台账审核 (" + ACTION + ")", targetSummary, kps, actionSummary,
                    plan, hash, null));
            String decId = created.entity().decisionId();
            audit(uid, conversationId, "submit-plan", ledger.docNumber(), decId, true,
                    "hitl=true, rows=" + v.toSubmit().size() + ", autoApproved=" + created.autoApproved());

            if (created.autoApproved()) {
                DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(decId, uid, ACTION, hash);
                if (!consume.ok()) {
                    return HitlToolHelper.formatConsumeError(consume, ACTION);
                }
                return "⚡ 已依据本会话同类授权自动放行提审（" + scopeDesc + "）。\n\n"
                        + doExecuteSubmit(uid, ledger, v, plan, decId, token, tenant, conversationId);
            }
            return hitlHelper.awaitAndExecute(created, actionSummary,
                    approvedId -> executeRound(uid, ledger.ledgerId(), approvedId, token, tenant, conversationId));
        }

        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, ACTION, hash);
        planCache.put(issued.confirmToken(), withExpiry(plan, issued.expiresInSeconds()));

        audit(uid, conversationId, "submit-plan", ledger.docNumber(), issued.confirmToken(), true,
                "rows=" + v.toSubmit().size() + ", skipped=" + v.skippedCategories().size()
                        + ", assignees=" + reviewerIds.size()
                        + (reviewerNote != null ? ", defaultAssignee=true" : ""));

        return renderPlan(ctx, ledger, v, issued, plan, null, reviewerNote, candidateNote);
    }

    // ── 二轮：携带令牌执行 ─────────────────────────────────────────────────────

    private String executeRound(Long uid, String ledgerId, String confirmToken,
                                String token, Long tenant, String conversationId) {
        String denied = permissionChecker.checkPermission(uid, ListFillNodesTool.PERMISSION);
        if (denied != null) {
            return denied;
        }
        if (hitlHelper != null && hitlHelper.isDecisionId(confirmToken)) {
            SubmitPlanCache.Plan plan = hitlHelper.loadStoredPlan(confirmToken, SubmitPlanCache.Plan.class);
            if (plan == null || !plan.ledgerId().equals(ledgerId)) {
                return "❌ 审批单无效或与台账不匹配（" + confirmToken + "）。请重新发起 submitLedgerForReview。";
            }
            String currentDraft = contextSupport.findDraftLedgerId(plan.phaseId(), plan.projectId(), token, tenant);
            if (!ledgerId.equals(currentDraft)) {
                return "❌ 该台账已不是您名下的草稿（可能已提交或状态变化），无法重复提审。";
            }
            FillContextSupport.LedgerView ledger = contextSupport.loadLedgerDetail(ledgerId, token, tenant);
            if (ledger.status() != 0) {
                return "❌ 台账 " + ledger.docNumber() + " 已不是草稿状态（当前：" + FillLabels.ledgerStatus(ledger.status())
                        + "），无法提审。";
            }
            FillPanelSupport.SubmitValidation v = panelSupport.validateForSubmit(ledger.rows());
            String hash = payloadHash(plan, ledger.rows());
            DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(confirmToken, uid, ACTION, hash);
            if (!consume.ok()) {
                return HitlToolHelper.formatConsumeError(consume, ACTION);
            }
            return doExecuteSubmit(uid, ledger, v, plan, confirmToken, token, tenant, conversationId);
        }

        SubmitPlanCache.Plan plan = planCache.get(confirmToken);
        if (plan == null || !plan.ledgerId().equals(ledgerId)) {
            return "❌ 确认令牌无效或与台账不匹配（从未签发/已过期/已执行/台账不符）。"
                    + "请重新发起 submitLedgerForReview 首轮调用生成新计划，再请用户确认。";
        }
        // 归属校验：ledgerId 必须仍是当前用户在该项目/阶段的草稿（get-draft 天然按登录人过滤）
        String currentDraft = contextSupport.findDraftLedgerId(plan.phaseId(), plan.projectId(), token, tenant);
        if (!ledgerId.equals(currentDraft)) {
            planCache.remove(confirmToken);
            return "❌ 该台账已不是您名下的草稿（可能已提交或状态变化），无法重复提审。"
                    + "可用 listFillNodes 查看当前状态。";
        }
        FillContextSupport.LedgerView ledger = contextSupport.loadLedgerDetail(ledgerId, token, tenant);
        if (ledger.status() != 0) {
            planCache.remove(confirmToken);
            return "❌ 台账 " + ledger.docNumber() + " 已不是草稿状态（当前：" + FillLabels.ledgerStatus(ledger.status())
                    + "），无法提审。";
        }

        // 现状漂移检测：重拉草稿重新预检，哈希比对
        FillPanelSupport.SubmitValidation v = panelSupport.validateForSubmit(ledger.rows());
        String hash = payloadHash(plan, ledger.rows());
        ConfirmTokenService.ConsumeResult verify = confirmTokenService.verifyAndConsume(
                confirmToken, uid, ACTION, hash);
        switch (verify) {
            case OK -> {
                // 执行（唯一路径）
            }
            case PAYLOAD_MISMATCH -> {
                planCache.remove(confirmToken);
                if (!v.violations().isEmpty()) {
                    return "❌ 草稿已变化且预检不再通过，原计划失效（未执行）。缺项：\n- "
                            + String.join("\n- ", v.violations())
                            + "\n请补齐后重新发起提审。";
                }
                return reissue(uid, ctxOf(plan), ledger, v, plan, token, tenant, conversationId,
                        "原确认令牌已失效（草稿内容与确认时不一致），已生成新计划，请重新征求用户确认。");
            }
            case EXPIRED -> {
                planCache.remove(confirmToken);
                if (!v.violations().isEmpty()) {
                    return "❌ 令牌已过期且草稿当前预检不通过（缺项：\n- " + String.join("\n- ", v.violations())
                            + "）。请补齐后重新发起提审。";
                }
                return reissue(uid, ctxOf(plan), ledger, v, plan, token, tenant, conversationId,
                        "原确认令牌已过期（5 分钟有效期），已生成新计划，请重新征求用户确认。");
            }
            case ALREADY_USED -> {
                return "❌ 该提审计划已执行过（令牌一次性）。请勿重复提交；可用 listFillNodes 查看台账当前状态。";
            }
            case USER_MISMATCH, ACTION_MISMATCH -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌校验失败（签发对象/动作不匹配），已作废。请重新发起首轮调用。";
            }
            default -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌不存在或已被取代。请重新发起 submitLedgerForReview 首轮调用。";
            }
        }

        return doExecuteSubmit(uid, ledger, v, plan, confirmToken, token, tenant, conversationId);
    }

    private String doExecuteSubmit(Long uid, FillContextSupport.LedgerView ledger,
                                 FillPanelSupport.SubmitValidation v, SubmitPlanCache.Plan plan,
                                 String confirmToken, String token, Long tenant, String conversationId) {
        // 执行提审（唯一写路径）
        ObjectNode body = contextSupport.mapper().createObjectNode();
        body.put("ledgerId", FillContextSupport.parseLong(ledger.ledgerId()));
        // needNext 必须为 true：主平台引擎仅在 needNext=true 且 nextNodes 非空时创建审核人
        // 待办；false 会把实例直接判"通过完成"（outcome=approved、零审批任务，提交人只剩已办）
        body.put("needNext", true);
        ArrayNode nodes = body.putArray("nextNodes");
        ObjectNode node = nodes.addObject();
        node.put("nodeName", NODE_NAME);
        node.put("approvalStrategy", STRATEGY);
        ArrayNode assignees = node.putArray("assigneeIds");
        for (String a : plan.assigneeIds()) {
            Long id = FillContextSupport.parseLong(a);
            if (id == null) {
                planCache.remove(confirmToken);
                return "❌ 审核人ID不合法（" + a + "），请重新通过 listReviewOptions 获取并确认。";
            }
            assignees.add(id);
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("opinion", plan.submitRemark());
        com.fasterxml.jackson.databind.node.ObjectNode vars = body.putObject("variables");
        variables.forEach(vars::put);

        RestActionClient.ActionResult r = restActionClient.post(
                "/doc/ledger/submit-for-review", body, token, tenant);
        contextSupport.requireOk(r, "提交审核");

        planCache.remove(confirmToken);
        nodeSelectionCache.remove(conversationId);
        audit(uid, conversationId, "submit", ledger.docNumber(), confirmToken, true,
                "rows=" + v.toSubmit().size() + ", assignees=" + plan.assigneeIds().size());

        StringBuilder sb = new StringBuilder("✅ 提交审核成功\n");
        sb.append("- 台账 **").append(ledger.docNumber()).append("**：草稿 → **审批中**\n");
        sb.append("- 本轮提交 ").append(v.toSubmit().size()).append(" 项");
        if (!v.skippedCategories().isEmpty()) {
            sb.append("，").append(v.skippedCategories().size()).append(" 项待上传留草稿（不进入本轮审核）");
        }
        sb.append('\n');
        sb.append("- 审核人：").append(displayAssignees(plan)).append("（部门审核 · 会签，任务已到达所选审核人）\n");
        sb.append("- 提交说明：").append(plan.submitRemark()).append('\n');
        sb.append("- 已发起 doc_collect 流程；提交后不可自动撤回，可在主平台或问我「我的待办」跟踪进度。");
        return sb.toString();
    }

    // ── 计划复用（令牌失效重出卡）──────────────────────────────────────────────

    private String reissue(Long uid, FillContextSupport.FillContext ctx, FillContextSupport.LedgerView ledger,
                           FillPanelSupport.SubmitValidation v, SubmitPlanCache.Plan plan,
                           String token, Long tenant, String conversationId, String lead) {
        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, ACTION, payloadHash(plan, ledger.rows()));
        planCache.put(issued.confirmToken(), withExpiry(plan, issued.expiresInSeconds()));
        audit(uid, conversationId, "submit-plan", ledger.docNumber(), issued.confirmToken(), true,
                "reissue; rows=" + v.toSubmit().size());
        return renderPlan(ctx, ledger, v, issued, plan, lead, null, null);
    }

    /** 计划快照补有效期（与令牌 TTL 对齐） */
    private static SubmitPlanCache.Plan withExpiry(SubmitPlanCache.Plan plan, long expiresInSeconds) {
        return new SubmitPlanCache.Plan(plan.ledgerId(), plan.projectId(), plan.phaseId(),
                plan.submitRemark(), plan.assigneeIds(), plan.assigneeNames(),
                plan.deptName(), System.currentTimeMillis() + expiresInSeconds * 1000);
    }

    private FillContextSupport.FillContext ctxOf(SubmitPlanCache.Plan plan) {
        // 二轮仅用定位串渲染计划文案；项目/阶段名以台账头为准
        return new FillContextSupport.FillContext(
                new FillContextSupport.ProjectRef(plan.projectId(), "", "", "", null, null),
                new FillContextSupport.PhaseTarget(plan.projectId(), plan.phaseId(), "", "", ""),
                List.of(), plan.ledgerId(), null);
    }

    // ── 计划渲染（设计 §7.8，纯对话文本）──────────────────────────────────────

    private String renderPlan(FillContextSupport.FillContext ctx, FillContextSupport.LedgerView ledger,
                              FillPanelSupport.SubmitValidation v, ConfirmTokenService.IssuedToken issued,
                              SubmitPlanCache.Plan plan, String lead, String reviewerNote, String candidateNote) {
        StringBuilder sb = new StringBuilder();
        if (lead != null) {
            sb.append("⚠️ ").append(lead).append("\n\n");
        }
        sb.append("⚠️ 提审执行计划（**尚未执行**，等待用户确认）\n");
        sb.append("- 台账：**").append(ledger.docNumber()).append("**（")
                .append(ledger.projectName().isBlank() ? "" : ledger.projectName() + " / ")
                .append(ledger.phaseName().isBlank() ? ctx.phase().phaseId() : ledger.phaseName()).append("）\n");
        sb.append("- 本轮提交 ").append(v.toSubmit().size()).append(" 项；待上传留草稿 ")
                .append(v.skippedCategories().size()).append(" 项");
        if (!v.skippedCategories().isEmpty()) {
            sb.append("（").append(String.join("、", v.skippedCategories())).append("）");
        }
        sb.append('\n');
        if (ledger.isSpecial()) {
            sb.append("- ℹ️ 特殊文件台账：需部门协同审核，当前版本仅单节点部门会签，多级配置请前往业务平台操作。\n");
        }
        sb.append("\n生效内容：\n");
        sb.append("| 节点 | 类别 | 状态 | 文件 | 摘要 |\n|---|---|---|---|---|\n");
        for (ObjectNode row : v.toSubmit()) {
            sb.append("| ").append(ledger.nodeNames().getOrDefault(row.path("nodeId").asText(""), "—"))
                    .append(" | ").append(row.path("category").asText(""))
                    .append(" | ").append(FillLabels.notApplicable(FillPatchMerger.intOrNull(row.path("notApplicable"))))
                    .append(" | ").append(orDash(row.path("fileName").asText("")))
                    .append(" | ").append(shortSummary(row.path("summary").asText("")))
                    .append(" |\n");
        }
        sb.append("\n提审配置：审核人 ").append(displayAssignees(plan))
                .append(reviewerNote == null ? "" : "（" + reviewerNote + "）")
                .append("（部门审核 · 会签）；提交说明「").append(shortSummary(plan.submitRemark())).append("」\n");
        if (candidateNote != null) {
            sb.append("- 备选常用审核人：").append(candidateNote).append('\n');
        }
        sb.append("- ❓ 审核人确认：如需更换，说「换审核人」或直接点姓名/部门，将重新出计划（旧令牌作废）；")
                .append("确认无误则回复「确认」执行提审。\n");
        sb.append("影响：台账 草稿 → 审批中，发起 doc_collect 流程；任务到达所选审核人；提交后不可自动撤回。\n");
        sb.append("确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效，一次性）\n");
        sb.append("请把以上计划完整展示给用户并等待明确同意；同意后传 ledgerId=")
                .append(ledger.ledgerId()).append(" + confirmToken 再次调用本工具执行。");
        return sb.toString();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /**
     * 提审 payload 哈希：SHA-256(生效行 [key,状态,文件URL,摘要] 列表 + ledgerId + 提交说明 + 审核人)。
     * 二轮重拉草稿重算比对——草稿漂移/参数偷换 → PAYLOAD_MISMATCH。
     */
    private String payloadHash(SubmitPlanCache.Plan plan, List<ObjectNode> rows) {
        List<Object> payload = new ArrayList<>();
        FillPanelSupport.SubmitValidation v = panelSupport.validateForSubmit(rows);
        for (ObjectNode row : v.toSubmit()) {
            payload.add(List.of(
                    row.path("id").asText(""),
                    row.path("nodeId").asText(""),
                    row.path("category").asText(""),
                    row.path("notApplicable").asInt(-1),
                    row.path("fileUrl").asText(""),
                    row.path("summary").asText("")));
        }
        payload.add(v.skippedCategories());
        payload.add(plan.ledgerId());
        payload.add(plan.submitRemark());
        payload.add(plan.assigneeIds());
        return confirmTokenService.payloadHash(payload);
    }

    private static String displayAssignees(SubmitPlanCache.Plan plan) {
        List<String> display = displayAssigneeList(plan);
        return String.join("、", display);
    }

    private static List<String> displayAssigneeList(SubmitPlanCache.Plan plan) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < plan.assigneeIds().size(); i++) {
            String name = i < plan.assigneeNames().size() ? plan.assigneeNames().get(i) : "";
            out.add(name.isBlank() ? plan.assigneeIds().get(i) : name + "(" + plan.assigneeIds().get(i) + ")");
        }
        return out;
    }

    private static String shortSummary(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private void audit(Long uid, String conversationId, String action, String businessObject,
                       String confirmToken, boolean success, String detail) {
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool(ACTION).action(action)
                .businessObject(businessObject).confirmToken(confirmToken)
                .decisionId(confirmToken != null && confirmToken.startsWith("dec_") ? confirmToken : null)
                .success(success)
                .errorMessage(success ? null : detail)
                .detail(success ? detail : null)
                .build());
    }
}

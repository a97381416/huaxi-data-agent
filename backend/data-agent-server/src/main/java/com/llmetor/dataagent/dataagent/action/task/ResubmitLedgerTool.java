package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.FunctionPermissionChecker;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.action.fill.FillContextSupport;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels;
import com.llmetor.dataagent.dataagent.hitl.HitlToolHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 驳回任务重新提交工具（M-E1，W2 写·高危：两段式确认令牌，协议见计划附录 B）。
 *
 * <p><b>首轮</b>（无 confirmToken）：权限→归属→状态守卫→PatchMerger 解析增量 patch→
 * 审核人校验（assigneeIds 必传，重提与提审一样要指定新一轮审核人——引擎 needNext=true
 * 且 nextNodes 非空才创建待办）→签发确认令牌（payload 哈希绑定<b>解析结果</b>
 * entryId+field+value 列表+taskId，非 patch 原文）→
 * 计划快照入 {@link ActionPlanCache}→以纯对话文本返回执行计划
 * （无确认卡，用户在对话里口头确认）。<b>零写副作用</b>。
 * <b>零写副作用</b>。
 *
 * <p><b>二轮</b>（带 confirmToken，patches/assigneeIds 不用再传）：重拉现状→重新解析→哈希比对
 * （现状漂移/参数偷换→令牌作废重出卡）→verifyAndConsume（一次性）→
 * <b>先 PUT stage-draft（把最终版刷进 STAGE，规避主后端"已有暂存时 resubmit 忽略
 * fileEntries"分支吞掉新改动）再 PUT resubmit（needNext=true + 单节点部门会签 nextNodes）</b>
 * →审计→回报。
 *
 * <p>resubmit 不幂等（推进审批流），令牌一次性 + 主后端 validateSubmitterTask/引擎校验双保险。
 */
@Slf4j
@Component
public class ResubmitLedgerTool {

    static final String ACTION = "resubmitLedger";
    /** 重提新一轮审核节点配置（与提审 SubmitLedgerForReviewTool 同构：单节点部门会签） */
    static final String NODE_NAME = "部门审核";
    static final String STRATEGY = "countersign";

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final TaskLedgerSupport support;
    private final PatchMerger patchMerger;
    private final ConfirmTokenService confirmTokenService;
    private final ActionPlanCache planCache;
    private final ActionAuditLogger auditLogger;
    private final FillContextSupport fillSupport;
    private final HitlToolHelper hitlHelper;

    public ResubmitLedgerTool(FunctionPermissionChecker permissionChecker,
                              RestActionClient restActionClient,
                              TaskLedgerSupport support,
                              PatchMerger patchMerger,
                              ConfirmTokenService confirmTokenService,
                              ActionPlanCache planCache,
                              ActionAuditLogger auditLogger,
                              FillContextSupport fillSupport) {
        this(permissionChecker, restActionClient, support, patchMerger,
                confirmTokenService, planCache, auditLogger, fillSupport, null);
    }

    @Autowired
    public ResubmitLedgerTool(FunctionPermissionChecker permissionChecker,
                              RestActionClient restActionClient,
                              TaskLedgerSupport support,
                              PatchMerger patchMerger,
                              ConfirmTokenService confirmTokenService,
                              ActionPlanCache planCache,
                              ActionAuditLogger auditLogger,
                              FillContextSupport fillSupport,
                              HitlToolHelper hitlHelper) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.support = support;
        this.patchMerger = patchMerger;
        this.confirmTokenService = confirmTokenService;
        this.planCache = planCache;
        this.auditLogger = auditLogger;
        this.fillSupport = fillSupport;
        this.hitlHelper = hitlHelper;
    }

    @Tool(name = "resubmitLedger",
            description = "驳回任务【重新提交】（高危：推进审批流，必须两段式确认）。" +
                    "① 首轮：传 taskId + patches（增量修改列表，格式同 stageDraft）；assigneeIds" +
                    "（本轮审核人用户ID列表）可选——不传时服务端自动取常用审核人第1名为默认并在计划中标注。" +
                    "【不要传 confirmToken】→ 返回执行计划与确认令牌（不执行）。把计划展示给用户等明确同意；" +
                    "用户要换审核人时：说人名→调 listReviewOptions 传 userNameKeyword 反查ID；说部门→传 deptId 查人；" +
                    "确定新审核人后直接重新首轮（同任务旧令牌自动作废）。" +
                    "② 二轮：用户明确同意后，只传 taskId + confirmToken（patches/assigneeIds 不用再传）→ 真正执行重提。" +
                    "用户取消时改调 cancelConfirm(confirmToken)。patch 字段白名单同 stageDraft。")
    public String resubmitLedger(
            @ToolParam(description = "任务ID（来自 listMyTasks，字符串原样传入）", required = true)
            String taskId,
            @ToolParam(description = "增量修改列表（首轮必传；二轮确认执行时不用传）。每条：category(必填)、" +
                    "field(必填)、value(必填)、node(可选消歧)、entryId(可选消歧)", required = false)
            List<PatchMerger.PatchSpec> patches,
            @ToolParam(description = "本轮审核人用户ID列表（首轮可选：不传=自动取常用审核人第1名为默认；" +
                    "可用 listReviewOptions 按姓名 userNameKeyword 反查或按部门 deptId 查人获取ID；" +
                    "二轮确认执行时不用传）", required = false)
            List<String> assigneeIds,
            @ToolParam(description = "确认令牌（二轮执行时传入；首轮严禁传，由服务端签发）", required = false)
            String confirmToken,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = TaskIds.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = TaskIds.parseLong(tenantId);

        try {
            if (confirmToken != null && !confirmToken.isBlank()) {
                return executeRound(uid, taskId.trim(), confirmToken.trim(), token, tenant, conversationId);
            }
            return planRound(uid, taskId, patches, assigneeIds, token, tenant, conversationId);
        } catch (TaskLedgerSupport.TaskFlowException e) {
            audit(uid, conversationId, "resubmit", null, null, false, e.getMessage());
            return e.getMessage();
        }
    }

    // ── 首轮：出计划（零写副作用）──────────────────────────────────────────────

    private String planRound(Long uid, String taskId, List<PatchMerger.PatchSpec> patches,
                             List<String> assigneeIds, String token, Long tenant, String conversationId) {
        if (hitlHelper != null) {
            String verifyDone = hitlHelper.checkVerifyOutcomeShortCircuit(ACTION, "/doc/ledger/stage-draft");
            if (verifyDone != null) {
                return verifyDone;
            }
        }
        String denied = permissionChecker.checkPermission(uid, StageDraftTool.PERMISSION);
        if (denied != null) {
            return denied;
        }
        if (patches == null || patches.isEmpty()) {
            return "❌ 缺少修改内容（patches 为空）。重提必须携带本轮要生效的增量修改；若用户只想原样重提，请在 patches 里至少包含一条明确修改，或引导用户在主平台操作。";
        }
        TaskLedgerSupport.TaskRef task = support.findMyTask(taskId, token, tenant);
        support.requireSubmitterTodoTask(task);
        TaskLedgerSupport.LedgerView view = support.loadLedgerView(task.ledgerId(), token, tenant);
        support.requireRejected(view);

        // 审核人：显式传入优先；未传 → 常用审核人第 1 名默认（按台账的项目/子阶段聚合）；
        // 无推荐 → 人话引导明确选人。引擎 needNext=true 且 nextNodes 非空才创建待办，审核人绝不能缺
        List<String> reviewers;
        String reviewerDisplay;
        String reviewerNote = null;
        String candidateNote = null;
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            String projectId = view.header().path("projectId").asText("");
            String phaseId = view.header().path("phaseId").asText("");
            List<ObjectNode> recommend = (projectId.isEmpty() || phaseId.isEmpty())
                    ? List.of() : fillSupport.recommendAssignees(projectId, phaseId, token, tenant);
            if (recommend.isEmpty()) {
                return "❌ 未指定本轮审核人，且该子阶段暂无常用审核人。" +
                        "请调 listReviewOptions 选人：用户点名姓名传 userNameKeyword 反查，或传 deptId 查部门人员；" +
                        "确认后带 assigneeIds 重新发起首轮。";
            }
            ObjectNode top = recommend.get(0);
            reviewers = List.of(top.path("userId").asText());
            reviewerDisplay = top.path("nickname").asText("") + "(ID:" + reviewers.get(0) + ")";
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
            reviewers = normalizeAssignees(assigneeIds);
            if (reviewers == null) {
                return "❌ 审核人ID不合法（须为用户ID）。请用 listReviewOptions 获取ID：" +
                        "用户点名姓名传 userNameKeyword 反查，或传 deptId 查部门人员。";
            }
            reviewerDisplay = String.join("、", reviewers);
        }

        PatchMerger.MergeResult merged = patchMerger.resolve(
                patches, view.baseRows(), view.nodeNames(), view.lockedIds());
        if (!merged.ok()) {
            return merged.guidance();
        }

        // 令牌哈希绑定解析结果（entryId+field+value 列表）+ taskId；二轮重拉现状重解析必须一致
        String hash = resolutionHash(task.taskId(), merged.resolved());
        if (hitlHelper != null && hitlHelper.isEnabled(ACTION)) {
            if (hitlHelper.isResumingVerifyRetry(ACTION)) {
                return doExecuteResubmit(uid, task, view, merged, reviewers,
                        com.llmetor.miniagentx.agent.ToolExecutionContext.currentResumeDecisionId(),
                        token, tenant, conversationId);
            }
            ActionPlanCache.Plan storedPlan = new ActionPlanCache.Plan(
                    task.taskId(), task.ledgerId(), List.copyOf(patches), List.copyOf(reviewers), 0L);
            String targetSummary = view.projectName() + " / " + view.phaseName() + "（" + view.docNumber() + "）";
            String scopeKey = ACTION + ":RESUBMIT:ledger=" + task.ledgerId() + ":assignees=" + String.join(",", reviewers);
            String scopeDesc = "仅限本会话内同台账（" + view.docNumber() + "）同审核人的重提";
            List<DecisionModels.KeyParamItem> kps = List.of(
                    HitlToolHelper.kp("台账单号", view.docNumber()),
                    HitlToolHelper.kp("任务ID", task.taskId()),
                    HitlToolHelper.kp("审核人", reviewerDisplay),
                    HitlToolHelper.kp("修改处数", merged.resolved().size() + " 处")
            );
            String actionSummary = "重新提交台账「" + view.docNumber() + "」（修改 " + merged.resolved().size() + " 处）";
            DecisionModels.CreateResult created = hitlHelper.createDecision(new DecisionModels.CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid,
                    DecisionModels.TYPE_PERMISSION, ACTION, scopeKey, scopeDesc, true,
                    "重新提交审核 (" + ACTION + ")", targetSummary, kps, actionSummary,
                    storedPlan, hash, null));
            String decId = created.entity().decisionId();
            audit(uid, conversationId, "resubmit-plan", view.docNumber() + "/task:" + task.taskId(),
                    decId, true, "hitl=true, patches=" + merged.resolved().size() + ", autoApproved=" + created.autoApproved());
            if (created.autoApproved()) {
                DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(decId, uid, ACTION, hash);
                if (!consume.ok()) {
                    return HitlToolHelper.formatConsumeError(consume, ACTION);
                }
                return "⚡ 已依据本会话同类授权自动放行重提（" + scopeDesc + "）。\n\n"
                        + doExecuteResubmit(uid, task, view, merged, reviewers, decId, token, tenant, conversationId);
            }
            return hitlHelper.awaitAndExecute(created, actionSummary,
                    approvedId -> executeRound(uid, task.taskId(), approvedId, token, tenant, conversationId));
        }

        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, ACTION, hash);
        planCache.put(issued.confirmToken(), task.taskId(), task.ledgerId(), patches, reviewers);

        audit(uid, conversationId, "resubmit-plan", view.docNumber() + "/task:" + task.taskId(),
                issued.confirmToken(), true, "patches=" + merged.resolved().size()
                        + ", assignees=" + reviewers.size());

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 重新提交执行计划（**尚未执行**，等待用户确认）\n");
        sb.append("- 台账：").append(view.docNumber()).append("（").append(view.projectName())
                .append(" / ").append(view.phaseName()).append("）\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        sb.append("- 审核人（").append(reviewers.size()).append(" 人，会签）：").append(reviewerDisplay);
        if (reviewerNote != null) {
            sb.append("（").append(reviewerNote).append("）");
        }
        sb.append('\n');
        if (candidateNote != null) {
            sb.append("- 备选常用审核人：").append(candidateNote).append('\n');
        }
        sb.append("- ❓ 审核人确认：要更换请说「换审核人」或直接点名姓名/部门（将重新出计划，旧令牌自动作废）；")
                .append("确认无误请回复「确认」。\n");
        sb.append("变更内容（").append(merged.resolved().size()).append(" 处）：\n");
        sb.append("| 类别 | 字段 | 从 | 改为 |\n|---|---|---|---|\n");
        for (PatchMerger.ResolvedPatch rp : merged.resolved()) {
            sb.append("| ").append(rp.category()).append(" | ").append(rp.field())
                    .append(" | ").append(rp.oldValue()).append(" | ").append(rp.newValue()).append(" |\n");
        }
        sb.append("影响：重提后台账「").append(DocLabels.ledgerStatus(view.status()))
                .append("」→「审批中」，任务办结，流程沿实例 `").append(view.currentInstanceId())
                .append("` 推进到所选审核人的待办（部门审核 · 会签）。\n");
        sb.append("确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效，一次性）\n");
        sb.append("请把以上计划完整展示给用户并等待明确同意；同意后只需传 taskId + confirmToken 再次调用本工具执行。");
        return sb.toString();
    }

    /** 审核人ID 规整：去空格、剔除空串；含非法（非数字）ID 返回 null（让 LLM 重新取 ID） */
    private static List<String> normalizeAssignees(List<String> assigneeIds) {
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String a : assigneeIds) {
            if (a == null || a.isBlank()) {
                continue;
            }
            String v = a.trim();
            if (TaskIds.parseLong(v) == null) {
                return null;
            }
            out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    // ── 二轮：携带令牌执行 ─────────────────────────────────────────────────────

    private String executeRound(Long uid, String taskId, String confirmToken,
                                String token, Long tenant, String conversationId) {
        String denied = permissionChecker.checkPermission(uid, StageDraftTool.PERMISSION);
        if (denied != null) {
            return denied;
        }
        if (hitlHelper != null && hitlHelper.isDecisionId(confirmToken)) {
            ActionPlanCache.Plan plan = hitlHelper.loadStoredPlan(confirmToken, ActionPlanCache.Plan.class);
            if (plan == null || !plan.taskId().equals(taskId)) {
                return "❌ 审批单无效或与任务不匹配（" + confirmToken + "）。请重新发起 resubmitLedger。";
            }
            if (plan.assigneeIds() == null || plan.assigneeIds().isEmpty()) {
                return "❌ 该审批单缺少审核人信息，请重新发起 resubmitLedger。";
            }
            TaskLedgerSupport.TaskRef task = support.findMyTask(taskId, token, tenant);
            support.requireSubmitterTodoTask(task);
            TaskLedgerSupport.LedgerView view = support.loadLedgerView(task.ledgerId(), token, tenant);
            support.requireRejected(view);
            PatchMerger.MergeResult merged = patchMerger.resolve(
                    plan.patches(), view.baseRows(), view.nodeNames(), view.lockedIds());
            if (!merged.ok()) {
                return "❌ 台账现状已发生变化，原审批单失效（未执行）。原因：\n" + merged.guidance();
            }
            String hash = resolutionHash(taskId, merged.resolved());
            DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(confirmToken, uid, ACTION, hash);
            if (!consume.ok()) {
                return HitlToolHelper.formatConsumeError(consume, ACTION);
            }
            return doExecuteResubmit(uid, task, view, merged, plan.assigneeIds(), confirmToken, token, tenant, conversationId);
        }

        ActionPlanCache.Plan plan = planCache.get(confirmToken);
        if (plan == null || !plan.taskId().equals(taskId)) {
            return "❌ 确认令牌无效（从未签发/已过期/已执行/任务不符/**已被更新的计划取代——换审核人或重新出计划后旧令牌自动作废**）。"
                    + "请用最新计划里的确认令牌执行；没有最新计划就重新发起 resubmitLedger 首轮调用。";
        }
        // 老计划（needNext=false 时代签发）无审核人：作废重出卡，别让重提悄悄丢待办
        if (plan.assigneeIds() == null || plan.assigneeIds().isEmpty()) {
            planCache.remove(confirmToken);
            return "❌ 该确认计划缺少审核人信息（旧版计划）。请重新发起 resubmitLedger 首轮调用（带 patches + assigneeIds）生成新计划。";
        }
        List<String> reviewers = plan.assigneeIds();

        TaskLedgerSupport.TaskRef task = support.findMyTask(taskId, token, tenant);
        support.requireSubmitterTodoTask(task);
        TaskLedgerSupport.LedgerView view = support.loadLedgerView(task.ledgerId(), token, tenant);
        support.requireRejected(view);

        // 重拉现状重新解析：竞态/现状漂移 → 哈希不匹配 → 令牌作废重出卡（D5）
        PatchMerger.MergeResult merged = patchMerger.resolve(
                plan.patches(), view.baseRows(), view.nodeNames(), view.lockedIds());
        if (!merged.ok()) {
            planCache.remove(confirmToken);
            confirmTokenService.revoke(confirmToken);
            return "❌ 台账现状已发生变化，原计划失效（未执行）。原因：\n" + merged.guidance()
                    + "\n请根据最新现状重新生成执行计划并再次征求用户确认。";
        }

        String hash = resolutionHash(taskId, merged.resolved());
        ConfirmTokenService.ConsumeResult verify = confirmTokenService.verifyAndConsume(
                confirmToken, uid, ACTION, hash);
        switch (verify) {
            case OK -> {
                // 执行（唯一路径）
            }
            case PAYLOAD_MISMATCH -> {
                // 现状漂移但新解析仍有效：直接重出计划（新令牌），一轮交互内闭环
                planCache.remove(confirmToken);
                return reissuePlan(uid, task, view, plan.patches(), merged, plan.assigneeIds(),
                        token, tenant, conversationId,
                        "原确认令牌已失效（台账现状与确认时不一致），已生成新计划，请重新征求用户确认。");
            }
            case EXPIRED -> {
                planCache.remove(confirmToken);
                return reissuePlan(uid, task, view, plan.patches(), merged, plan.assigneeIds(),
                        token, tenant, conversationId,
                        "原确认令牌已过期（5 分钟有效期），已生成新计划，请重新征求用户确认。");
            }
            case ALREADY_USED -> {
                return "❌ 该计划已执行过（令牌一次性）。请勿重复提交；可用 listMyTasks 或 getTaskDetail 查看当前状态。";
            }
            case USER_MISMATCH, ACTION_MISMATCH -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌校验失败（签发对象/动作不匹配），已作废。请重新发起首轮调用。";
            }
            case NOT_FOUND -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌不存在或已被取代。请重新发起 resubmitLedger 首轮调用。";
            }
            default -> {
                planCache.remove(confirmToken);
                return "❌ 确认令牌校验失败，请重新发起首轮调用。";
            }
        }

        return doExecuteResubmit(uid, task, view, merged, reviewers, confirmToken, token, tenant, conversationId);
    }

    private String doExecuteResubmit(Long uid, TaskLedgerSupport.TaskRef task, TaskLedgerSupport.LedgerView view,
                                     PatchMerger.MergeResult merged, List<String> reviewers, String confirmToken,
                                     String token, Long tenant, String conversationId) {
        // 全量组装：基准行（STAGE 优先）+ 解析结果 + 锁定行原样追加
        List<ObjectNode> fullRows = patchMerger.assembleFullRows(
                view.baseRows(), merged.resolved(), view.lockedRows());

        // 写 1/2：先把最终版刷进 STAGE——主后端"已有暂存时 resubmit 忽略 fileEntries"，
        // 不刷的话历史暂存会吞掉本轮新改动
        ObjectNode stageBody = support.mapper().createObjectNode();
        stageBody.put("ledgerId", task.ledgerId());
        stageBody.put("taskId", task.taskId());
        ArrayNode stageArr = stageBody.putArray("fileEntries");
        fullRows.forEach(stageArr::add);
        if (hitlHelper != null) {
            String stageStatus = hitlHelper.executeWithVerifyOutcome(
                    ACTION,
                    "重提落最终暂存",
                    view.docNumber(),
                    uid,
                    conversationId,
                    "/doc/ledger/stage-draft",
                    true,
                    () -> {
                        RestActionClient.ActionResult stage = restActionClient.put(
                                "/doc/ledger/stage-draft", stageBody, token, tenant);
                        support.requireOk(stage, "落最终暂存");
                        return stage;
                    },
                    r -> "STAGE_OK"
            );
            if (!"STAGE_OK".equals(stageStatus)) {
                return stageStatus;
            }
        } else {
            RestActionClient.ActionResult stage = restActionClient.put(
                    "/doc/ledger/stage-draft", stageBody, token, tenant);
            support.requireOk(stage, "落最终暂存");
        }

        // 写 2/2：重提（resubmit 走 STAGE 分支，以刚刷入的最终版为准）。
        // needNext=true + nextNodes 必传：引擎只在此时创建审核人待办，否则实例直接"通过完成"
        ObjectNode resubmitBody = support.mapper().createObjectNode();
        resubmitBody.put("ledgerId", task.ledgerId());
        resubmitBody.put("taskId", task.taskId());
        resubmitBody.put("needNext", true);
        ArrayNode nextNodes = resubmitBody.putArray("nextNodes");
        ObjectNode node = nextNodes.addObject();
        node.put("nodeName", NODE_NAME);
        node.put("approvalStrategy", STRATEGY);
        ArrayNode assigneeArr = node.putArray("assigneeIds");
        reviewers.forEach(a -> assigneeArr.add(Long.parseLong(a)));
        ArrayNode arr = resubmitBody.putArray("fileEntries");
        fullRows.forEach(arr::add);
        if (hitlHelper != null) {
            return hitlHelper.executeWithVerifyOutcome(
                    ACTION,
                    "重新提交审核",
                    view.docNumber(),
                    uid,
                    conversationId,
                    "/doc/ledger/resubmit",
                    false,
                    () -> {
                        RestActionClient.ActionResult r = restActionClient.put(
                                "/doc/ledger/resubmit", resubmitBody, token, tenant);
                        support.requireOk(r, "重新提交");
                        return r;
                    },
                    r -> formatResubmitSuccess(uid, task, view, merged, reviewers, fullRows, confirmToken, conversationId)
            );
        }
        RestActionClient.ActionResult r = restActionClient.put(
                "/doc/ledger/resubmit", resubmitBody, token, tenant);
        support.requireOk(r, "重新提交");
        return formatResubmitSuccess(uid, task, view, merged, reviewers, fullRows, confirmToken, conversationId);
    }

    private String formatResubmitSuccess(Long uid, TaskLedgerSupport.TaskRef task, TaskLedgerSupport.LedgerView view,
                                         PatchMerger.MergeResult merged, List<String> reviewers,
                                         List<ObjectNode> fullRows, String confirmToken, String conversationId) {
        planCache.remove(confirmToken);
        audit(uid, conversationId, "resubmit", view.docNumber() + "/task:" + task.taskId(),
                confirmToken, true, "patches=" + merged.resolved().size()
                        + ", fullRows=" + fullRows.size() + ", assignees=" + reviewers.size());

        StringBuilder sb = new StringBuilder("✅ 重新提交完成\n");
        sb.append("- 台账 ").append(view.docNumber()).append("：")
                .append(DocLabels.ledgerStatus(view.status())).append(" → **审批中**\n");
        sb.append("- 生效修改 ").append(merged.resolved().size()).append(" 处；任务已办结，本轮审核人（会签）：")
                .append(String.join("、", reviewers))
                .append("，待办已送达（实例 `").append(view.currentInstanceId()).append("`）\n");
        sb.append("- 后续：可在主平台任务中心跟踪进度，或随时问我「我的待办/已办」。");
        return sb.toString();
    }

    // ── 计划复用（令牌失效重出卡：现状解析仍有效时一轮闭环）───────────────────

    private String reissuePlan(Long uid, TaskLedgerSupport.TaskRef task, TaskLedgerSupport.LedgerView view,
                               List<PatchMerger.PatchSpec> patches, PatchMerger.MergeResult merged,
                               List<String> reviewers, String token, Long tenant, String conversationId, String lead) {
        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(
                uid, ACTION, resolutionHash(task.taskId(), merged.resolved()));
        planCache.put(issued.confirmToken(), task.taskId(), task.ledgerId(), patches, reviewers);
        audit(uid, conversationId, "resubmit-plan", view.docNumber() + "/task:" + task.taskId(),
                issued.confirmToken(), true, "reissue; patches=" + merged.resolved().size());

        StringBuilder sb = new StringBuilder("⚠️ ").append(lead).append("\n\n");
        sb.append("重新提交执行计划（**尚未执行**）\n");
        sb.append("- 台账：").append(view.docNumber()).append("（").append(view.projectName())
                .append(" / ").append(view.phaseName()).append("）\n");
        sb.append("变更内容（").append(merged.resolved().size()).append(" 处）：\n");
        sb.append("| 类别 | 字段 | 从 | 改为 |\n|---|---|---|---|\n");
        for (PatchMerger.ResolvedPatch rp : merged.resolved()) {
            sb.append("| ").append(rp.category()).append(" | ").append(rp.field())
                    .append(" | ").append(rp.oldValue()).append(" | ").append(rp.newValue()).append(" |\n");
        }
        sb.append("新确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效）。请把新计划完整展示给用户并等待明确同意。");
        return sb.toString();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /**
     * 解析结果哈希（D5：绑定解析结果而非 patch 原文）：SHA-256(canonical JSON of
     * [[entryId, field, typedValue]...] + taskId，顺序稳定)。
     */
    private String resolutionHash(String taskId, List<PatchMerger.ResolvedPatch> resolved) {
        List<Object> payload = new ArrayList<>();
        for (PatchMerger.ResolvedPatch rp : resolved) {
            payload.add(List.of(rp.entryId(), rp.field(), String.valueOf(rp.typedValue())));
        }
        payload.add(taskId);
        return confirmTokenService.payloadHash(payload);
    }

    private void audit(Long uid, String conversationId, String action, String businessObject,
                       String confirmToken, boolean success, String detail) {
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("resubmitLedger").action(action)
                .businessObject(businessObject).confirmToken(confirmToken)
                .decisionId(confirmToken != null && confirmToken.startsWith("dec_") ? confirmToken : null)
                .success(success)
                .errorMessage(success ? null : detail)
                .detail(success ? detail : null)
                .build());
    }
}

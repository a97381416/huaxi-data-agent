package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.action.fill.FillContextSupport;
import com.llmetor.dataagent.dataagent.action.task.DocLabels;
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
 * 审核通过工具（M-E3，W2 写·高危：两段式确认令牌，协议见计划附录 B）。
 *
 * <p><b>首轮</b>（无 confirmToken）：归属→状态守卫（待办+审批节点+台账审批中）→流程分流——
 * <ul>
 *   <li>常规流程 / 会签非末位：needNext=false（常规一审即定稿；非末位通过后还需等待他人）；</li>
 *   <li>特殊流程 + 本节点末位：finishFlow=true（须 task_coaudit_roles 角色命中，无权则拒绝）
 *       或 nextApproverIds（下级节点固定"部门审核·会签"，对齐 PC NodeConfigSection）；
 *       两者都缺 → 不签令牌，返回决策引导 + 常用审批人推荐；</li>
 *   <li>PM 角色（task_pm_opinion_roles 命中）：逐条 PM 意见按类别/序号解析到行集 entryId——
 *       已取得行缺省自动赋字典文案（对齐后端 updateObtainedPmOpinion），空缺/不涉及行必填，
 *       缺失 → 列出行序号让助手追问；非 PM 角色忽略该参数。</li>
 * </ul>
 * → 签发确认令牌（payload 哈希绑定最终请求体）→ 计划快照入 {@link ReviewPlanCache}
 * → 纯对话文本返回执行计划（零写副作用）。
 *
 * <p><b>二轮</b>（只传 taskId + confirmToken）：重查任务/台账现状（漂移→作废）→
 * verifyAndConsume（一次性；过期→同计划重签令牌一轮闭环）→ doc 类走
 * POST /doc/ledger-process/approve，非 doc 类走 POST /task-center/task/approve →审计→回报。
 */
@Slf4j
@Component
public class ApproveTaskTool {

    static final String ACTION = "approveTask";
    /** 下级审批节点配置（对齐 PC ApproveDialog：节点名/策略前端已固定，不再暴露选择） */
    static final String NODE_NAME = "部门审核";
    static final String STRATEGY = "countersign";
    /** 总审核意见缺省（对齐 PC ApproveDialog 预填"通过。"） */
    static final String DEFAULT_OPINION = "通过。";

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ConfirmTokenService confirmTokenService;
    private final ReviewPlanCache planCache;
    private final ActionAuditLogger auditLogger;
    private final FillContextSupport fillSupport;
    private final HitlToolHelper hitlHelper;

    public ApproveTaskTool(RestActionClient restActionClient,
                           ReviewSupport support,
                           ConfirmTokenService confirmTokenService,
                           ReviewPlanCache planCache,
                           ActionAuditLogger auditLogger,
                           FillContextSupport fillSupport) {
        this(restActionClient, support, confirmTokenService, planCache, auditLogger, fillSupport, null);
    }

    @Autowired
    public ApproveTaskTool(RestActionClient restActionClient,
                           ReviewSupport support,
                           ConfirmTokenService confirmTokenService,
                           ReviewPlanCache planCache,
                           ActionAuditLogger auditLogger,
                           FillContextSupport fillSupport,
                           HitlToolHelper hitlHelper) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.confirmTokenService = confirmTokenService;
        this.planCache = planCache;
        this.auditLogger = auditLogger;
        this.fillSupport = fillSupport;
        this.hitlHelper = hitlHelper;
    }

    /** PM 逐条意见（category 或 rowNo 定位，二者至少其一；rowNo 来自 getReviewDetail 条目表序号） */
    public record PmOpinionSpec(String category, Integer rowNo, String opinion) {
    }

    @Tool(name = "approveTask",
            description = "审核【通过】（高危：推进/办结审批流，必须两段式确认）。" +
                    "① 首轮：传 taskId；opinion 总体审核意见（缺省「通过。」）。" +
                    "PM 角色可传 pmOpinions 逐条意见（每条：category 或 rowNo[getReviewDetail 条目表序号] + opinion；" +
                    "空缺/不涉及行的意见必填，已取得行缺省自动赋「资料已复核，通过」）。" +
                    "特殊流程且是本节点最后一位审核人时：finishFlow=true（仅有权终结的角色可用）" +
                    "或 nextApproverIds（下级审批人用户ID列表，部门会签；ID 可用 listCoauditOptions 查）。" +
                    "【不要传 confirmToken】→ 返回执行计划与确认令牌（不执行）。" +
                    "② 二轮：用户明确同意后，只传 taskId + confirmToken → 真正执行通过。" +
                    "用户取消时改调 cancelConfirm(confirmToken)。")
    public String approveTask(
            @ToolParam(description = "审核任务ID（来自 listReviewTasks，字符串原样传入）", required = true)
            String taskId,
            @ToolParam(description = "总体审核意见（可选，缺省「通过。」）", required = false)
            String opinion,
            @ToolParam(description = "PM 逐条意见（仅 PM 角色需要；每条 category 或 rowNo + opinion）", required = false)
            List<PmOpinionSpec> pmOpinions,
            @ToolParam(description = "下级审批人用户ID列表（仅特殊流程且本节点末位需要；下级为部门会签；" +
                    "ID 用 listCoauditOptions 查询）", required = false)
            List<String> nextApproverIds,
            @ToolParam(description = "true=不再需要下级、直接办结（仅特殊流程且您是有权终结的角色）", required = false)
            Boolean finishFlow,
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
            return planRound(uid, taskId, opinion, pmOpinions, nextApproverIds, finishFlow,
                    token, tenant, conversationId);
        } catch (ReviewSupport.TaskFlowException e) {
            audit(uid, conversationId, "approve", taskId, null, false, e.getMessage());
            return e.getMessage();
        }
    }

    // ── 首轮：出计划（零写副作用）──────────────────────────────────────────────

    private String planRound(Long uid, String taskId, String opinion, List<PmOpinionSpec> pmOpinions,
                             List<String> nextApproverIds, Boolean finishFlow,
                             String token, Long tenant, String conversationId) {
        ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
        support.requireApprovalTodoTask(task);
        String op = (opinion == null || opinion.isBlank()) ? DEFAULT_OPINION : opinion.trim();

        if (!task.docLedger()) {
            // 非 doc 类任务：仅总体意见，一审即办结
            ObjectNode body = support.mapper().createObjectNode();
            body.put("taskId", Long.parseLong(task.taskId()));
            ObjectNode vars = body.putObject("variables");
            vars.put("opinion", op);
            body.put("needNext", false);
            String note = "任务办结，流程按引擎规则推进";
            return issueAndRenderPlan(uid, task, ACTION, body, note, op, null, false, null,
                    token, tenant, conversationId);
        }

        support.requireLedgerInReview(task);
        ReviewSupport.RoleGates gates = support.loadRoleGates(token, tenant);
        boolean special = task.isSpecial();
        boolean lastInNode = requireLastInNode(task, token, tenant);

        // PM 逐条意见：角色门控 + 行集解析 + 必填校验
        List<ResolvedOpinion> resolvedOpinions = null;
        if (pmOpinions != null && !pmOpinions.isEmpty() && !gates.pmOpinion()) {
            return "❌ 您的角色无需逐条填写 PM 意见（该职责由项目经理承担）。请去掉 pmOpinions 参数，"
                    + "直接给出总体审核意见后重新发起。";
        }
        if (gates.pmOpinion()) {
            // 解析失败直接抛 TaskFlowException（含缺失清单），由外层 catch 原样返回
            resolvedOpinions = resolvePmOpinions(task, pmOpinions, token, tenant, gates.obtainedPmOpinionDefault());
        }

        // 下级安排判定（仅特殊流程 + 本节点末位需要）
        boolean needNext = false;
        List<String> approvers = null;
        String approverNames = null;
        String coauditNote = null;
        if (special && lastInNode) {
            boolean wantFinish = Boolean.TRUE.equals(finishFlow);
            List<String> normalized = normalizeApproverIds(nextApproverIds);
            if (wantFinish && normalized != null) {
                return "❌ finishFlow 与 nextApproverIds 不能同时指定（要么直接办结，要么交给下一级）。请与用户确认后重新发起。";
            }
            if (wantFinish) {
                if (!gates.canFinish()) {
                    return "❌ 您的角色无权终结特殊流程（「不再需要下级」仅限配置允许的领导角色）。"
                            + "请提供 nextApproverIds（下级审批人，可用 listCoauditOptions 查询）后重新发起。";
                }
                needNext = false;
                coauditNote = "用户明确终结：不再需要下级，通过后流程办结";
            } else if (normalized != null) {
                needNext = true;
                approvers = normalized;
                approverNames = String.join("、", normalized);
            } else {
                // 决策引导（不签令牌）：有权终结给两选项，无权只给选人
                return finishDecisionGuide(task, gates, token, tenant);
            }
        } else if (special) {
            coauditNote = "本节点还有其他审核人未审（会签）：您通过后节点需等其余审核人全部通过才推进，下级安排由最后一位审核人决定";
            if (finishFlow != null || (nextApproverIds != null && !nextApproverIds.isEmpty())) {
                coauditNote += "（您传入的下级/终结参数将被忽略——非末位审核人不决定下级安排）";
            }
        }

        // 组装最终请求体（doc：ledger-process 通道）
        ObjectNode body = support.mapper().createObjectNode();
        body.put("ledgerId", Long.parseLong(task.ledgerId()));
        ObjectNode vars = body.putObject("variables");
        vars.put("opinion", op);
        body.put("needNext", needNext);
        if (needNext) {
            ArrayNode nextNodes = body.putArray("nextNodes");
            ObjectNode node = nextNodes.addObject();
            node.put("nodeName", NODE_NAME);
            node.put("approvalStrategy", STRATEGY);
            ArrayNode assigneeArr = node.putArray("assigneeIds");
            approvers.forEach(a -> assigneeArr.add(Long.parseLong(a)));
        }
        if (resolvedOpinions != null) {
            ArrayNode opArr = body.putArray("pmOpinions");
            for (ResolvedOpinion ro : resolvedOpinions) {
                ObjectNode item = opArr.addObject();
                item.put("entryId", Long.parseLong(ro.entryId()));
                item.put("opinion", ro.opinion());
            }
        }

        String note = needNext
                ? "推进到下一级「部门审核」（会签）：" + approverNames
                : (coauditNote != null ? coauditNote : "实例办结，台账置「已通过」，发起人收到通过通知");
        String pmNote = resolvedOpinions == null ? null : "PM 意见 " + resolvedOpinions.size() + " 条";
        return issueAndRenderPlan(uid, task, ACTION, body, note, op, pmNote, needNext, approverNames,
                token, tenant, conversationId);
    }

    /**
     * PM 逐条意见解析：pmOpinions（category/rowNo 定位）合并行集，产出全量意见列表。
     * 规则（对齐 PC PmOpinionSection）：已取得行缺省自动赋字典文案；空缺/不涉及/其他行必填；
     * 任一行缺意见抛 {@link ReviewSupport.TaskFlowException}（附缺失清单与行序号）。
     */
    private List<ResolvedOpinion> resolvePmOpinions(ReviewSupport.ReviewTaskRef task,
                                                    List<PmOpinionSpec> specs,
                                                    String token, Long tenant, String obtainedDefault) {
        JsonNode rows = loadPmOpinionRows(task, token, tenant);
        List<JsonNode> rowList = new ArrayList<>();
        rows.forEach(rowList::add);

        // spec 解析：rowNo 优先（getReviewDetail 序号），category 兜底（精确→唯一包含）
        java.util.Map<Integer, String> provided = new java.util.LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (specs != null) {
            for (PmOpinionSpec s : specs) {
                String value = s.opinion() == null ? "" : s.opinion().trim();
                Integer idx = locateRow(rowList, s, errors);
                if (idx == null) {
                    continue;
                }
                if (value.isEmpty()) {
                    errors.add("第 " + idx + " 行（" + rowList.get(idx - 1).path("category").asText() + "）：意见为空");
                    continue;
                }
                provided.put(idx, value);
            }
        }

        // 合并全量：显式意见 > 行上已有意见 > 已取得默认文案；其余缺失 → 错误清单
        List<ResolvedOpinion> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 1; i <= rowList.size(); i++) {
            JsonNode row = rowList.get(i - 1);
            String category = row.path("category").asText("");
            int na = row.path("notApplicable").asInt(-1);
            String opinion = provided.get(i);
            if (opinion == null || opinion.isEmpty()) {
                opinion = row.path("pmOpinion").asText("");
            }
            if ((opinion == null || opinion.isBlank())) {
                if (na == 0) {
                    opinion = obtainedDefault;
                } else {
                    missing.add("第 " + i + " 行：" + category + "（" + DocLabels.notApplicable(na) + "）缺 PM 意见");
                    continue;
                }
            }
            out.add(new ResolvedOpinion(row.path("entryId").asText(""), category, opinion));
        }
        if (!errors.isEmpty() || !missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("❌ PM 意见校验未通过：\n");
            for (String e : errors) {
                sb.append("- ").append(e).append('\n');
            }
            for (String m : missing) {
                sb.append("- ").append(m).append('\n');
            }
            sb.append("空缺/不涉及/其他行必须填写 PM 意见（说明原因或结论）；已取得行缺省自动为「")
                    .append(obtainedDefault).append("」。请补齐后重新发起首轮。");
            throw new ReviewSupport.TaskFlowException(sb.toString());
        }
        return out;
    }

    /** rowNo/category → 行序号（1-based）；定位失败把原因写入 errors，返回 null */
    private Integer locateRow(List<JsonNode> rowList, PmOpinionSpec spec, List<String> errors) {
        if (spec.rowNo() != null) {
            int no = spec.rowNo();
            if (no < 1 || no > rowList.size()) {
                errors.add("rowNo=" + no + " 超出条目范围（1~" + rowList.size() + "）");
                return null;
            }
            return no;
        }
        String category = spec.category() == null ? "" : spec.category().trim();
        if (category.isEmpty()) {
            errors.add("一条 PM 意见既无 category 也无 rowNo，无法定位");
            return null;
        }
        List<Integer> exact = new ArrayList<>();
        List<Integer> contains = new ArrayList<>();
        for (int i = 1; i <= rowList.size(); i++) {
            String name = rowList.get(i - 1).path("category").asText("");
            if (name.equals(category)) {
                exact.add(i);
            } else if (name.contains(category)) {
                contains.add(i);
            }
        }
        if (exact.size() == 1 || (exact.isEmpty() && contains.size() == 1)) {
            return (exact.size() == 1) ? exact.get(0) : contains.get(0);
        }
        errors.add("类别「" + category + "」" + (exact.size() + contains.size() == 0 ? "未找到"
                : "命中多行，请改用 rowNo（getReviewDetail 条目表序号）定位"));
        return null;
    }

    private JsonNode loadPmOpinionRows(ReviewSupport.ReviewTaskRef task, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger-process/pm-opinion/list",
                ReviewSupport.query("ledgerId", task.ledgerId()), token, tenantId);
        support.requireOk(r, "查询待审条目");
        return r.data().path("rows");
    }

    /** approve-check：特殊流程必须拿到确定的会签位置结论（fail-closed，选下级不能靠猜） */
    private boolean requireLastInNode(ReviewSupport.ReviewTaskRef task, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/approve-check",
                ReviewSupport.query("taskId", task.taskId()), token, tenantId);
        support.requireOk(r, "审批前检查（会签位置）");
        return r.data().path("isLastInNode").asBoolean(true);
    }

    /** 特殊流程缺决策引导：列出两个选项 + 常用审批人推荐（不签令牌，不产生副作用） */
    private String finishDecisionGuide(ReviewSupport.ReviewTaskRef task, ReviewSupport.RoleGates gates,
                                       String token, Long tenant) {
        StringBuilder sb = new StringBuilder("⚠️ 特殊流程通过前需要用户决策（**尚未生成执行计划**）：\n");
        sb.append("- 台账：").append(task.ledgerHeader().path("docNumber").asText("")).append("（特殊流程）\n");
        if (gates.canFinish()) {
            sb.append("- 选项 A：不再需要下级审核，直接办结 → 传 finishFlow=true\n");
        } else {
            sb.append("- 您的角色无权直接办结特殊流程，必须指定下一级审批人\n");
        }
        sb.append("- 选项 B：指定下一级审批人（部门会签）→ 传 nextApproverIds（用户ID列表）\n");
        String projectId = task.ledgerHeader().path("projectId").asText("");
        String phaseId = task.ledgerHeader().path("phaseId").asText("");
        if (!projectId.isEmpty() && !phaseId.isEmpty()) {
            List<ObjectNode> recommend = fillSupport.recommendAssignees(projectId, phaseId, token, tenant);
            if (!recommend.isEmpty()) {
                sb.append("常用审批人推荐：\n");
                int cap = Math.min(recommend.size(), 5);
                for (int i = 0; i < cap; i++) {
                    ObjectNode c = recommend.get(i);
                    sb.append("- ").append(c.path("nickname").asText(""))
                            .append("（").append(c.path("deptName").asText("—")).append("）ID:")
                            .append(c.path("userId").asText()).append('\n');
                }
                sb.append("也可用 listCoauditOptions 按姓名/部门查人。\n");
            }
        }
        sb.append("请向用户转达选项并确认后，重新发起 approveTask 首轮。");
        return sb.toString();
    }

    /** 审核人 ID 规整：去空格、剔除空串；含非法（非数字）ID 返回 null（让 LLM 重新取 ID） */
    private static List<String> normalizeApproverIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String a : ids) {
            if (a == null || a.isBlank()) {
                continue;
            }
            String v = a.trim();
            if (ReviewSupport.parseLong(v) == null) {
                return null;
            }
            out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    // ── 计划签发与渲染（approve/reject/partial 共用协议）──────────────────────

    private String issueAndRenderPlan(Long uid, ReviewSupport.ReviewTaskRef task, String action,
                                      ObjectNode body, String outcomeNote, String opinion,
                                      String pmNote, boolean needNext, String approverNames,
                                      String token, Long tenant, String conversationId) {
        String canonical = canonicalJson(body);
        String hash = confirmTokenService.payloadHash(List.of(action, task.taskId(), canonical));

        if (hitlHelper != null && hitlHelper.isEnabled(ACTION)) {
            String docNum = task.docLedger() ? task.ledgerHeader().path("docNumber").asText("") : "";
            String targetSummary = task.title() + (docNum.isBlank() ? "" : "（" + docNum + "）");
            List<DecisionModels.KeyParamItem> kps = HitlToolHelper.cleanKeyParams(java.util.Arrays.asList(
                    HitlToolHelper.kp("任务ID", task.taskId()),
                    HitlToolHelper.kp("总体意见", opinion),
                    needNext ? HitlToolHelper.kp("下级审批人", approverNames) : null,
                    HitlToolHelper.kp("影响", outcomeNote)
            ));
            String actionSummary = "审核通过「" + task.title() + "」（" + opinion + "）";
            HitlToolHelper.ReviewStoredPlan stored = new HitlToolHelper.ReviewStoredPlan(
                    action, task.taskId(), task.ledgerId(), canonical, outcomeNote);
            DecisionModels.CreateResult created = hitlHelper.createDecision(new DecisionModels.CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid,
                    DecisionModels.TYPE_PERMISSION, ACTION, null, null, false,
                    "审核通过 (" + ACTION + ")", targetSummary, kps, actionSummary,
                    stored, hash, null));
            String decId = created.entity().decisionId();
            audit(uid, conversationId, "approve-plan", task, decId, true,
                    "hitl=true, needNext=" + needNext);
            return hitlHelper.awaitAndExecute(created, actionSummary,
                    approvedId -> executeRound(uid, task.taskId(), approvedId, token, tenant, conversationId));
        }

        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, action, hash);
        planCache.put(issued.confirmToken(), action, task.taskId(), task.ledgerId(), body, outcomeNote);

        audit(uid, conversationId, "approve-plan", task, issued.confirmToken(), true,
                "needNext=" + needNext + (pmNote == null ? "" : ", " + pmNote));

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 审核通过执行计划（**尚未执行**，等待用户确认）\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        if (task.docLedger()) {
            sb.append("- 台账：").append(task.ledgerHeader().path("docNumber").asText(""))
                    .append("｜流程类型：").append(task.isSpecial() ? "特殊流程" : "常规流程").append('\n');
        }
        sb.append("- 动作：**通过**｜总体意见：「").append(opinion).append("」\n");
        if (pmNote != null) {
            sb.append("- PM 逐条意见：").append(pmNote).append("（空缺/不涉及行已要求填写，已取得行自动默认文案）\n");
        }
        if (needNext) {
            sb.append("- 下级安排：推进到「").append(NODE_NAME).append("」（部门会签）：").append(approverNames).append('\n');
        }
        sb.append("- 影响：").append(outcomeNote).append('\n');
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
                return "❌ 审批单无效或与任务不匹配（" + confirmToken + "）。请重新发起 approveTask。";
            }
            ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
            try {
                support.requireApprovalTodoTask(task);
                support.requireLedgerInReview(task);
            } catch (ReviewSupport.TaskFlowException e) {
                return "❌ 台账/任务现状已发生变化，原审批单失效（未执行）。原因：" + e.getMessage();
            }
            String hash = confirmTokenService.payloadHash(List.of(ACTION, taskId, stored.requestBodyJson()));
            DecisionModels.ConsumeOutcome consume = hitlHelper.verifyAndConsume(confirmToken, uid, ACTION, hash);
            if (!consume.ok()) {
                return HitlToolHelper.formatConsumeError(consume, ACTION);
            }
            String path = task.docLedger() ? "/doc/ledger-process/approve" : "/task-center/task/approve";
            RestActionClient.ActionResult r = restActionClient.post(path, reqBody, token, tenant);
            if (!r.success()) {
                audit(uid, conversationId, "approve", task, confirmToken, false, r.msg());
                return "❌ 审核通过执行失败：" + r.msg();
            }
            audit(uid, conversationId, "approve", task, confirmToken, true, "executed");
            return "✅ 审核通过完成\n- 任务 " + task.title() + "：已办结\n- 影响：" + stored.outcomeNote() + "\n";
        }

        ReviewPlanCache.Plan plan = planCache.get(confirmToken);
        if (plan == null || !ACTION.equals(plan.action()) || !taskId.equals(plan.taskId())) {
            return "❌ 确认令牌无效（从未签发/已过期/已执行/任务不符/**已被更新的计划取代**）。"
                    + "请用最新计划里的确认令牌执行；没有最新计划就重新发起 approveTask 首轮调用。";
        }

        // 现状守卫：任务仍在我待办、台账仍审批中（漂移→作废）
        ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
        try {
            support.requireApprovalTodoTask(task);
            support.requireLedgerInReview(task);
        } catch (ReviewSupport.TaskFlowException e) {
            planCache.remove(confirmToken);
            confirmTokenService.revoke(confirmToken);
            return "❌ 台账/任务现状已发生变化，原计划失效（未执行）。原因：" + e.getMessage()
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
                // 现状守卫已通过、请求体未变：同计划重签新令牌，一轮闭环
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
                return "❌ 确认令牌校验失败（" + verify + "），已作废。请重新发起 approveTask 首轮调用。";
            }
        }

        // 提交（唯一写路径）：doc → ledger-process；非 doc → task-center
        String path = task.docLedger()
                ? "/doc/ledger-process/approve"
                : "/task-center/task/approve";
        RestActionClient.ActionResult r = restActionClient.post(path, plan.requestBody(), token, tenant);
        if (!r.success()) {
            planCache.remove(confirmToken);
            audit(uid, conversationId, "approve", task, confirmToken, false, r.msg());
            return "❌ 审核通过执行失败：" + r.msg() + "\n（计划已作废；如需重试请重新发起首轮。）";
        }
        planCache.remove(confirmToken);
        audit(uid, conversationId, "approve", task, confirmToken, true, "executed");

        StringBuilder sb = new StringBuilder("✅ 审核通过完成\n");
        sb.append("- 任务 ").append(task.title()).append("：已办结\n");
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
        audit(uid, conversationId, "approve-plan", task, issued.confirmToken(), true, "reissue");
        StringBuilder sb = new StringBuilder("⚠️ ").append(lead)
                .append("，已生成新计划（**尚未执行**）：\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        sb.append("- 影响：").append(plan.outcomeNote()).append('\n');
        sb.append("新确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效）。请把新计划完整展示给用户并等待明确同意。");
        return sb.toString();
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    /** 键排序规范化 JSON（payload 哈希与计划快照共用，保证跨轮稳定） */
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
                .tool("approveTask").action(action)
                .businessObject(biz).confirmToken(confirmToken)
                .success(success)
                .errorMessage(success ? null : detail)
                .detail(success ? detail : null)
                .build());
    }

    /** 已解析的 PM 意见行（entryId 字符串承载，组装请求体时再转 Long） */
    private record ResolvedOpinion(String entryId, String category, String opinion) {
    }
}

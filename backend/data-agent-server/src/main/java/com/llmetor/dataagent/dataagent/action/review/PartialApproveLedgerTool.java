package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.action.task.DocLabels;
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
 * 台账部分通过工具（M-E3，W2 写·高危：两段式确认令牌，协议见计划附录 B）。
 *
 * <p>仅资料台账任务（doc）；仅 PM 角色（task_pm_opinion_roles 命中，对齐 PC
 * PmOpinionSection——部分通过正是"逐条意见表里出现否行"的产物）。行为对齐
 * POST /doc/ledger-process/partial-approve：decisions 逐行精确落库（是→已通过、
 * 否→已驳回），**固定退回发起人**补正（不创建下级审批节点），台账置「部分通过」、
 * 重提任务带 partial_flag 橙标。
 *
 * <p><b>首轮</b>：归属→状态守卫→角色门控→行集加载→decisions 覆盖校验（对齐前端
 * 全量行提交：未覆盖行默认"通过"，但已取得行缺省文案、空缺/不涉及行意见必填）→
 * 否行意见必填且不得是同意话术（同意文案不能当退回原因，对齐前端切"否"清空逻辑）→
 * 签发确认令牌→计划快照入 {@link ReviewPlanCache}（零写副作用）。
 * <b>二轮</b>：现状守卫→verifyAndConsume→POST partial-approve→审计→回报。
 */
@Slf4j
@Component
public class PartialApproveLedgerTool {

    static final String ACTION = "partialApproveLedger";
    /** 引擎留痕总意见缺省（对齐 PC ApproveDialog 有"否"行时自动切"部分通过。"） */
    static final String DEFAULT_OPINION = "部分通过。";

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ConfirmTokenService confirmTokenService;
    private final ReviewPlanCache planCache;
    private final ActionAuditLogger auditLogger;
    private final HitlToolHelper hitlHelper;

    public PartialApproveLedgerTool(RestActionClient restActionClient,
                                    ReviewSupport support,
                                    ConfirmTokenService confirmTokenService,
                                    ReviewPlanCache planCache,
                                    ActionAuditLogger auditLogger) {
        this(restActionClient, support, confirmTokenService, planCache, auditLogger, null);
    }

    @Autowired
    public PartialApproveLedgerTool(RestActionClient restActionClient,
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

    /** 逐行判定（category 或 rowNo 定位，二者至少其一；rowNo 来自 getReviewDetail 条目表序号） */
    public record DecisionSpec(String category, Integer rowNo, Boolean pass, String opinion) {
    }

    @Tool(name = "partialApproveLedger",
            description = "台账审核【部分通过】（高危：退回审批流，必须两段式确认）。" +
                    "适用：用户对部分文件类别不通过、其余通过（如「第 2、5 条不行，其余通过」）。" +
                    "① 首轮：传 taskId + decisions（逐条：category 或 rowNo[getReviewDetail 条目表序号] + " +
                    "pass[true/false] + opinion[不通过行必填，说清退回原因]）；opinion 总体意见缺省「部分通过。」。" +
                    "未覆盖的行默认按通过处理（已取得行自动补「资料已复核，通过」；空缺/不涉及行仍需意见，缺了会报错）。" +
                    "【不要传 confirmToken】→ 返回执行计划与确认令牌（不执行）。" +
                    "② 二轮：用户明确同意后，只传 taskId + confirmToken → 真正执行。" +
                    "注意：部分通过固定退回申报人补正重提，不走下一级审批。")
    public String partialApproveLedger(
            @ToolParam(description = "审核任务ID（来自 listReviewTasks，字符串原样传入）", required = true)
            String taskId,
            @ToolParam(description = "逐行判定列表（每条 category 或 rowNo + pass + opinion）", required = true)
            List<DecisionSpec> decisions,
            @ToolParam(description = "总体审核意见（可选，缺省「部分通过。」）", required = false)
            String opinion,
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
            return planRound(uid, taskId, decisions, opinion, token, tenant, conversationId);
        } catch (ReviewSupport.TaskFlowException e) {
            audit(uid, conversationId, "partial", taskId, null, false, e.getMessage());
            return e.getMessage();
        }
    }

    // ── 首轮：出计划（零写副作用）──────────────────────────────────────────────

    private String planRound(Long uid, String taskId, List<DecisionSpec> decisions, String opinion,
                             String token, Long tenant, String conversationId) {
        if (hitlHelper != null) {
            String verifyDone = hitlHelper.checkVerifyOutcomeShortCircuit(ACTION);
            if (verifyDone != null) {
                return verifyDone;
            }
        }
        ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
        support.requireApprovalTodoTask(task);
        if (!task.docLedger()) {
            return "❌ 部分通过仅适用于资料台账任务（当前任务无关联台账）。非台账任务请用 approveTask/rejectTask。";
        }
        support.requireLedgerInReview(task);
        ReviewSupport.RoleGates gates = support.loadRoleGates(token, tenant);
        if (!gates.pmOpinion()) {
            return "❌ 部分通过需要按文件类别逐条判定，该职责由项目经理承担（您的角色无需也无法逐条判定）。"
                    + "如需退回申报人，请用 rejectTask 驳回。";
        }
        if (decisions == null || decisions.isEmpty()) {
            return "❌ 缺少逐行判定（decisions 为空）。请把用户的判定展开成逐条列表（category 或 rowNo + pass + opinion）再发起。";
        }

        // 行集加载与定位（rowNo=序号优先，category 兜底；与 approveTask 同款协议）
        com.fasterxml.jackson.databind.JsonNode rowsNode = loadPmOpinionRows(task, token, tenant);
        List<com.fasterxml.jackson.databind.JsonNode> rowList = new ArrayList<>();
        rowsNode.forEach(rowList::add);
        if (rowList.isEmpty()) {
            return "❌ 该台账没有待审条目（全部行已通过或待上传），无可用部分通过语义。请改用 approveTask。";
        }

        Map<Integer, DecisionSpec> provided = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (DecisionSpec s : decisions) {
            Integer idx = locateRow(rowList, s, errors);
            if (idx == null) {
                continue;
            }
            if (s.pass() == null) {
                errors.add("第 " + idx + " 行（" + rowList.get(idx - 1).path("category").asText() + "）：未指定 pass（true/false）");
                continue;
            }
            provided.put(idx, s);
        }

        // 组装全量 decisions：显式判定 > 行上已有意见 > 已取得默认文案；否行意见校验
        String obtainedDefault = gates.obtainedPmOpinionDefault();
        String op = (opinion == null || opinion.isBlank()) ? DEFAULT_OPINION : opinion.trim();
        ArrayNode arr = support.mapper().createArrayNode();
        List<String> missing = new ArrayList<>();
        int passCount = 0;
        int rejectCount = 0;
        for (int i = 1; i <= rowList.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode row = rowList.get(i - 1);
            String category = row.path("category").asText("");
            int na = row.path("notApplicable").asInt(-1);
            DecisionSpec s = provided.get(i);
            boolean pass = s != null && Boolean.TRUE.equals(s.pass());
            String rowOpinion = s == null || s.opinion() == null || s.opinion().isBlank()
                    ? row.path("pmOpinion").asText("")
                    : s.opinion().trim();
            if (!pass) {
                // 否行：意见必填，且不得是同意话术（对齐前端切"否"清空同意文案）
                if (rowOpinion.isBlank() || isAgreePhrase(rowOpinion, obtainedDefault)) {
                    errors.add("第 " + i + " 行：" + category + "——不通过行的意见必填，且不能用同意话术（要说清退回原因、要改什么）");
                    continue;
                }
                rejectCount++;
            } else {
                if (rowOpinion.isBlank()) {
                    if (na == 0) {
                        rowOpinion = obtainedDefault;
                    } else {
                        missing.add("第 " + i + " 行：" + category + "（" + DocLabels.notApplicable(na)
                                + "）按通过处理但缺 PM 意见（空缺/不涉及行必须说明）");
                        continue;
                    }
                }
                passCount++;
            }
            ObjectNode item = arr.addObject();
            item.put("entryId", Long.parseLong(row.path("entryId").asText()));
            item.put("pass", pass);
            item.put("opinion", rowOpinion);
        }
        if (!errors.isEmpty() || !missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("❌ 逐行判定校验未通过：\n");
            for (String e : errors) {
                sb.append("- ").append(e).append('\n');
            }
            for (String m : missing) {
                sb.append("- ").append(m).append('\n');
            }
            sb.append("请补齐后重新发起首轮（rowNo 对应 getReviewDetail 条目表序号）。");
            throw new ReviewSupport.TaskFlowException(sb.toString());
        }

        // 组装最终请求体
        ObjectNode body = support.mapper().createObjectNode();
        body.put("ledgerId", Long.parseLong(task.ledgerId()));
        ObjectNode vars = body.putObject("variables");
        vars.put("opinion", op);
        body.set("decisions", arr);

        String outcomeNote = "通过 " + passCount + " 条、退回 " + rejectCount + " 条；台账置「部分通过」，"
                + "固定退回申报人补正（重提任务带部分通过标记），不走下一级审批";
        String canonical = canonicalJson(body);
        String hash = confirmTokenService.payloadHash(List.of(ACTION, task.taskId(), canonical));
        if (hitlHelper != null && hitlHelper.isEnabled(ACTION)) {
            if (hitlHelper.isResumingVerifyRetry(ACTION)) {
                return doExecutePartial(uid, task, body, outcomeNote,
                        com.llmetor.miniagentx.agent.ToolExecutionContext.currentResumeDecisionId(),
                        token, tenant, conversationId);
            }
            String docNum = task.ledgerHeader().path("docNumber").asText("");
            String targetSummary = task.title() + (docNum.isBlank() ? "" : "（" + docNum + "）");
            List<DecisionModels.KeyParamItem> kps = List.of(
                    HitlToolHelper.kp("任务ID", task.taskId()),
                    HitlToolHelper.kp("判定统计", "通过 " + passCount + " 条、退回 " + rejectCount + " 条"),
                    HitlToolHelper.kp("总体意见", op),
                    HitlToolHelper.kp("影响", outcomeNote)
            );
            String actionSummary = "台账部分通过「" + task.title() + "」（通过 " + passCount + " 条，退回 " + rejectCount + " 条）";
            HitlToolHelper.ReviewStoredPlan stored = new HitlToolHelper.ReviewStoredPlan(
                    ACTION, task.taskId(), task.ledgerId(), canonical, outcomeNote);
            DecisionModels.CreateResult created = hitlHelper.createDecision(new DecisionModels.CreateDecisionRequest(
                    conversationId, conversationId, null, null, uid,
                    DecisionModels.TYPE_PERMISSION, ACTION, null, null, false,
                    "部分通过 (" + ACTION + ")", targetSummary, kps, actionSummary,
                    stored, hash, null));
            String decId = created.entity().decisionId();
            audit(uid, conversationId, "partial-plan", task, decId, true,
                    "hitl=true, pass=" + passCount + ", reject=" + rejectCount);
            return hitlHelper.awaitAndExecute(created, actionSummary,
                    approvedId -> executeRound(uid, task.taskId(), approvedId, token, tenant, conversationId));
        }

        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(uid, ACTION, hash);
        planCache.put(issued.confirmToken(), ACTION, task.taskId(), task.ledgerId(), body, outcomeNote);

        audit(uid, conversationId, "partial-plan", task, issued.confirmToken(), true,
                "pass=" + passCount + ", reject=" + rejectCount);

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 部分通过执行计划（**尚未执行**，等待用户确认）\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        sb.append("- 台账：").append(task.ledgerHeader().path("docNumber").asText("")).append('\n');
        sb.append("- 总体意见：「").append(op).append("」\n");
        sb.append("逐行判定（").append(rowList.size()).append(" 行）：\n");
        sb.append("| # | 类别 | 判定 | 意见 |\n|---|------|------|------|\n");
        for (int i = 1; i <= rowList.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode row = rowList.get(i - 1);
            DecisionSpec s = provided.get(i);
            boolean pass = s != null && Boolean.TRUE.equals(s.pass());
            String rowOpinion = pass && (s == null || s.opinion() == null || s.opinion().isBlank())
                    && row.path("pmOpinion").asText("").isBlank() && row.path("notApplicable").asInt(-1) == 0
                    ? obtainedDefault + "（默认）"
                    : (s != null && s.opinion() != null && !s.opinion().isBlank() ? s.opinion()
                    : row.path("pmOpinion").asText(""));
            sb.append("| ").append(i).append(" | ").append(row.path("category").asText(""))
                    .append(" | ").append(pass ? "✅ 通过" : "❌ 退回")
                    .append(" | ").append(rowOpinion.isBlank() ? "—" : rowOpinion).append(" |\n");
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
                return "❌ 审批单无效或与任务不匹配（" + confirmToken + "）。请重新发起 partialApproveLedger。";
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
            return doExecutePartial(uid, task, reqBody, stored.outcomeNote(), confirmToken, token, tenant, conversationId);
        }

        ReviewPlanCache.Plan plan = planCache.get(confirmToken);
        if (plan == null || !ACTION.equals(plan.action()) || !taskId.equals(plan.taskId())) {
            return "❌ 确认令牌无效（从未签发/已过期/已执行/任务不符/**已被更新的计划取代**）。"
                    + "请用最新计划里的确认令牌执行；没有最新计划就重新发起 partialApproveLedger 首轮调用。";
        }

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
                return "❌ 确认令牌校验失败（" + verify + "），已作废。请重新发起 partialApproveLedger 首轮调用。";
            }
        }

        return doExecutePartial(uid, task, plan.requestBody(), plan.outcomeNote(), confirmToken, token, tenant, conversationId);
    }

    private String doExecutePartial(Long uid, ReviewSupport.ReviewTaskRef task,
                                    com.fasterxml.jackson.databind.JsonNode reqBody,
                                    String outcomeNote, String confirmToken,
                                    String token, Long tenant, String conversationId) {
        String bizId = task.ledgerHeader() != null && !task.ledgerHeader().path("docNumber").asText("").isBlank()
                ? task.ledgerHeader().path("docNumber").asText("") : "task:" + task.taskId();
        if (hitlHelper != null) {
            return hitlHelper.executeWithVerifyOutcome(
                    ACTION,
                    "台账部分通过",
                    bizId,
                    uid,
                    conversationId,
                    () -> restActionClient.post("/doc/ledger-process/partial-approve", reqBody, token, tenant),
                    r -> formatPartialResult(uid, task, outcomeNote, confirmToken, conversationId, r)
            );
        }
        RestActionClient.ActionResult r = restActionClient.post("/doc/ledger-process/partial-approve", reqBody, token, tenant);
        return formatPartialResult(uid, task, outcomeNote, confirmToken, conversationId, r);
    }

    private String formatPartialResult(Long uid, ReviewSupport.ReviewTaskRef task, String outcomeNote,
                                       String confirmToken, String conversationId, RestActionClient.ActionResult r) {
        planCache.remove(confirmToken);
        if (!r.success()) {
            audit(uid, conversationId, "partial", task, confirmToken, false, r.msg());
            return "❌ 部分通过执行失败：" + r.msg();
        }
        audit(uid, conversationId, "partial", task, confirmToken, true, "executed");
        return "✅ 部分通过完成\n- 任务 " + task.title() + "：已办结（部分通过）\n- 影响：" + outcomeNote + "\n";
    }

    /** 同计划重签新令牌（现状守卫已通过、参数未变） */
    private String reissuePlan(Long uid, ReviewSupport.ReviewTaskRef task, ReviewPlanCache.Plan plan,
                               String token, Long tenant, String conversationId, String lead) {
        ConfirmTokenService.IssuedToken issued = confirmTokenService.issue(
                uid, ACTION, confirmTokenService.payloadHash(
                        List.of(ACTION, task.taskId(), plan.requestBodyJson())));
        planCache.put(issued.confirmToken(), ACTION, task.taskId(), task.ledgerId(),
                plan.requestBody(), plan.outcomeNote());
        audit(uid, conversationId, "partial-plan", task, issued.confirmToken(), true, "reissue");
        StringBuilder sb = new StringBuilder("⚠️ ").append(lead)
                .append("，已生成新计划（**尚未执行**）：\n");
        sb.append("- 任务：").append(task.title()).append("（任务ID `").append(task.taskId()).append("`）\n");
        sb.append("- 影响：").append(plan.outcomeNote()).append('\n');
        sb.append("新确认令牌：`").append(issued.confirmToken()).append("`（")
                .append(issued.expiresInSeconds()).append(" 秒内有效）。请把新计划完整展示给用户并等待明确同意。");
        return sb.toString();
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    private com.fasterxml.jackson.databind.JsonNode loadPmOpinionRows(ReviewSupport.ReviewTaskRef task,
                                                                      String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger-process/pm-opinion/list",
                ReviewSupport.query("ledgerId", task.ledgerId()), token, tenantId);
        support.requireOk(r, "查询待审条目");
        return r.data().path("rows");
    }

    /** rowNo/category → 行序号（1-based）；定位失败把原因写入 errors，返回 null */
    private Integer locateRow(List<com.fasterxml.jackson.databind.JsonNode> rowList,
                              DecisionSpec spec, List<String> errors) {
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
            errors.add("一条判定既无 category 也无 rowNo，无法定位");
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

    /** 同意话术判定（否行不得复用：默认文案/纯"通过/同意"） */
    private static boolean isAgreePhrase(String opinion, String obtainedDefault) {
        String v = opinion.trim();
        if (v.equals(obtainedDefault.trim())) {
            return true;
        }
        return v.equals("通过") || v.equals("通过。") || v.equals("同意") || v.equals("同意。")
                || v.equals(DEFAULT_OPINION.trim());
    }

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
            biz = t.ledgerHeader() != null ? t.ledgerHeader().path("docNumber").asText("") + "/task:" + t.taskId()
                    : "task:" + t.taskId();
        } else {
            biz = "task:" + taskOrId;
        }
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("partialApproveLedger").action(action)
                .businessObject(biz).confirmToken(confirmToken)
                .decisionId(confirmToken != null && confirmToken.startsWith("dec_") ? confirmToken : null)
                .success(success)
                .errorMessage(success ? null : detail)
                .detail(success ? detail : null)
                .build());
    }
}

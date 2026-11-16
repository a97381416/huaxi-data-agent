package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import com.llmetor.dataagent.dataagent.action.task.DocLabels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审核详情聚合工具（M-E3，读）：一次调用拼出「待审条目 + 流程配置 + 审批轨迹 + 审批链」。
 *
 * <p>聚合五个主后端接口（BFF 在工具层，D1）：
 * <ol>
 *   <li>todo/done-page 扫描定位任务（归属校验）+ /doc/ledger/get 台账头探测</li>
 *   <li>GET /doc/ledger-process/pm-opinion/list：待审行集（主后端已滤掉待上传/已通过行，
 *       与 PC 审核弹框 PmOpinionSection 同源）+ 状态统计</li>
 *   <li>GET /task-center/task/approve-check：是否本节点最后一位审核人（选下级区显隐的关键）</li>
 *   <li>GET /doc/ledger-process/audit-logs：审批轨迹（谁/何时/意见/附件）</li>
 *   <li>GET /task-center/task/approve-chain：审批链（=驳回目标候选清单）</li>
 * </ol>
 * 展示口径对齐 ApproveDialog：流程类型（常规=通过即定稿 / 特殊=需安排下级）、我的角色
 * （PM 逐条意见 / 部门·领导总意见）、会签位置、能否终结，全部由服务端算好直接给结论，
 * LLM 不自行判角色（角色判定依赖字典与权限，属服务端守卫职责）。
 */
@Slf4j
@Component
public class GetReviewDetailTool {

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ActionAuditLogger auditLogger;

    public GetReviewDetailTool(RestActionClient restActionClient,
                               ReviewSupport support,
                               ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "getReviewDetail",
            description = "查看一条审核任务的详情：待审文件条目逐条清单（类别、文件、状态、摘要、上次PM意见）、" +
                    "流程类型（常规=通过即定稿；特殊=通过后需选下级审批人）、我的审核角色（PM=需逐条填意见 / " +
                    "部门·领导=只填总意见）、会签位置（是否本节点最后一位审核人）、审批轨迹、审批链（驳回目标候选）。" +
                    "适用：审核前必看（禁止跳过本工具直接出审核计划）。" +
                    "taskId 来自 listReviewTasks 返回的任务ID（字符串，原样传入）。")
    public String getReviewDetail(
            @ToolParam(description = "任务ID（来自 listReviewTasks 返回，字符串原样传入）", required = true)
            String taskId,
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
            ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
            if (task == null) {
                return "❌ 未在您的待办/已办中找到该任务（任务 ID 无效、已办结或不属于您）。请先用 listReviewTasks 确认任务 ID。";
            }
            if ("submitter".equals(task.nodeType())) {
                return "ℹ️ 任务 ` " + task.taskId() + " ` 是**重新填写任务**（您自己申报的台账被驳回/部分通过待重提），"
                        + "不属于审核操作。请切换到申报技能（declarant-workflow）处理：查被驳明细、改资料、重新提交。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 审核详情（任务ID `").append(task.taskId()).append("`）\n");
            sb.append("- 任务：").append(task.title()).append("｜类别：").append(task.categoryLabel()).append('\n');
            if (!task.todo()) {
                sb.append("- ⚠️ 该任务已在您的**已办**中（结论：").append(DocLabels.todoApproveStatus(task.approveStatus()))
                        .append("），以下为当时审核时的留痕信息。\n");
            }
            if (task.initiatorName() != null && !task.initiatorName().isBlank()) {
                sb.append("- 提交人：").append(task.initiatorName()).append("｜创建 ").append(task.createTime()).append('\n');
            }

            if (!task.docLedger()) {
                // 非 doc 类任务：无台账语义，给通用信息 + 轨迹
                sb.append("\n> ℹ️ 该任务为非资料台账类（无文件条目），仅支持总体审核意见（通过/驳回）。\n");
                appendTraceByInstance(sb, task.instanceId(), token, tenant);
                audit(uid, conversationId, task, "plain");
                return sb.toString();
            }

            JsonNode header = task.ledgerHeader();
            sb.append("- 台账批次：**").append(header.path("docNumber").asText("")).append("**（")
                    .append(DocLabels.ledgerStatus(ReviewSupport.intOrNull(header.path("status")))).append("）\n");
            sb.append("- 项目/阶段：").append(header.path("projectName").asText(""));
            String stage = header.path("stageName").asText("");
            if (!stage.isBlank()) {
                sb.append(" / ").append(stage);
            }
            sb.append(" / ").append(header.path("phaseName").asText("")).append('\n');

            boolean special = task.isSpecial();
            sb.append("- **流程类型：").append(special ? "特殊流程**（紧急/特殊文件——通过后需安排下级协同审核；逐级审核直到有权终结的角色放行）"
                    : "常规流程**（通过后流程即办结，无需选下级）").append('\n');

            // 角色门控 + 会签位置（服务端算好结论，LLM 照述）
            ReviewSupport.RoleGates gates = support.loadRoleGates(token, tenant);
            sb.append("- 我的审核角色：").append(gates.pmOpinion()
                    ? "**PM审核**（需按文件类别逐条填写 PM 意见；已取得行默认文案可直接用）"
                    : "**部门/领导审核**（只填总体审核意见；各节点填报信息与 PM 意见只读可查）").append('\n');
            if (special) {
                sb.append("- 终结权限：").append(gates.canFinish()
                        ? "**有**（可选择「不再需要下级，直接办结」）"
                        : "无（通过时必须指定下一级审批人，无法自行终结流程）").append('\n');
            }

            // 待审行集（主后端已滤掉 待上传(-1)/已通过(2) 行，与 PC 审核弹框同源）
            RestActionClient.ActionResult pr = restActionClient.get(
                    "/doc/ledger-process/pm-opinion/list", Map.of("ledgerId", task.ledgerId()), token, tenant);
            if (pr.success() && pr.data().isObject()) {
                JsonNode stats = pr.data().path("stats");
                sb.append("\n### 待审文件条目（已剔除待上传与已通过行）\n");
                sb.append("统计：已取得 ").append(stats.path("obtained").asInt(0))
                        .append(" 个文件｜空缺 ").append(stats.path("vacant").asInt(0))
                        .append(" 项｜不涉及 ").append(stats.path("notInvolved").asInt(0)).append(" 项\n");
                sb.append("| # | 类别 | 文件 | 状态 | 摘要 | 上次PM意见 |\n");
                sb.append("|---|------|------|------|------|------------|\n");
                int i = 0;
                for (JsonNode row : pr.data().path("rows")) {
                    i++;
                    sb.append("| ").append(i)
                            .append(" | ").append(row.path("category").asText(""))
                            .append(" | ").append(orDash(row.path("fileName").asText("")))
                            .append(" | ").append(notApplicableLabel(ReviewSupport.intOrNull(row.path("notApplicable"))))
                            .append(" | ").append(orDash(row.path("summary").asText("")))
                            .append(" | ").append(orDash(row.path("pmOpinion").asText("")))
                            .append(" |\n");
                }
                if (i == 0) {
                    sb.append("（无待审条目）\n");
                }
            }

            // 会签位置（是否本节点最后一位审核人）
            appendApproveCheck(sb, task.taskId(), token, tenant);
            // 审批轨迹
            appendAuditLogs(sb, task.ledgerId(), token, tenant);
            // 审批链（=驳回目标候选）
            appendChain(sb, task.taskId(), token, tenant, task.initiatorName());

            // 行动指引
            sb.append("\n### 审核指引\n");
            sb.append("- 通过：approveTask（普通通过即办结 / 特殊流程需 nextApproverIds 或 finishFlow；PM 角色可带逐条 pmOpinions）。\n");
            sb.append("- 驳回：rejectTask（意见必填；目标三选一：originator 发起人[默认]/parent 上级/specified 指定节点）。\n");
            sb.append("- 部分通过（PM）：partialApproveLedger（某几条不通过、其余通过，固定退回发起人补正）。\n");

            audit(uid, conversationId, task, "doc");
            return sb.toString();
        } catch (ReviewSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** approve-check：是否本节点最后一位审核人（或签/会签推进语义提示） */
    private void appendApproveCheck(StringBuilder sb, String taskId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/approve-check", Map.of("taskId", taskId), token, tenantId);
        if (!r.success() || r.data() == null || r.data().isMissingNode()) {
            return;
        }
        boolean last = r.data().path("isLastInNode").asBoolean(true);
        String strategy = r.data().path("approvalStrategy").asText("");
        sb.append("\n- 会签位置：").append(last
                ? "**您是本节点最后一位待审审核人**（您通过后节点完成并推进）"
                : "本节点还有其他审核人未审（策略：" + ReviewSupport.strategyLabel(strategy) + "；您通过后还需等待他们）");
        sb.append('\n');
    }

    /** 台账审批轨迹（audit-logs：谁/何时/结论/意见/关联文件数） */
    private void appendAuditLogs(StringBuilder sb, String ledgerId, String token, Long tenantId) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger-process/audit-logs", Map.of("ledgerId", ledgerId), token, tenantId);
        if (!r.success() || !r.data().isArray() || r.data().isEmpty()) {
            return;
        }
        sb.append("\n### 审批轨迹\n");
        for (JsonNode log : r.data()) {
            sb.append("- ").append(log.path("endTime").asText(log.path("createTime").asText("")))
                    .append("　").append(log.path("nodeName").asText(""))
                    .append("·").append(log.path("assigneeName").asText(""))
                    .append("：**").append(auditLogStatus(ReviewSupport.intOrNull(log.path("approveStatus"))))
                    .append("**");
            String opinion = log.path("opinion").asText("");
            if (!opinion.isBlank()) {
                sb.append("——「").append(opinion).append("」");
            }
            sb.append('\n');
        }
    }

    /** 非 doc 任务的通用轨迹（list-by-instance） */
    private void appendTraceByInstance(StringBuilder sb, String instanceId, String token, Long tenantId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/list-by-instance", Map.of("instanceId", instanceId), token, tenantId);
        if (!r.success() || !r.data().isArray() || r.data().isEmpty()) {
            return;
        }
        sb.append("\n### 审批轨迹\n");
        for (JsonNode log : r.data()) {
            sb.append("- ").append(log.path("endTime").asText(log.path("createTime").asText("")))
                    .append("　").append(log.path("assigneeName").asText(""))
                    .append("：**").append(DocLabels.traceApproveStatus(ReviewSupport.intOrNull(log.path("approveStatus"))))
                    .append("**");
            String opinion = log.path("opinion").asText("");
            if (!opinion.isBlank()) {
                sb.append("——「").append(opinion).append("」");
            }
            sb.append('\n');
        }
    }

    /** 审批链（驳回目标候选；发起人在首位、当前节点在末尾） */
    private void appendChain(StringBuilder sb, String taskId, String token, Long tenantId, String initiatorName) {
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/approve-chain", Map.of("taskId", taskId), token, tenantId);
        if (!r.success() || !r.data().isArray() || r.data().isEmpty()) {
            return;
        }
        sb.append("\n### 审批链（驳回目标候选；顺序=发起人 → 当前）\n");
        sb.append(support.chainCandidates(r.data(), ""));
    }

    private static String auditLogStatus(Integer v) {
        if (v == null) {
            return "待审核";
        }
        return switch (v) {
            case 1 -> "通过";
            case 2 -> "驳回";
            case 4 -> "部分通过";
            default -> "待审核";
        };
    }

    private static String notApplicableLabel(Integer v) {
        if (v == null) {
            return DocLabels.notApplicable(null);
        }
        return DocLabels.notApplicable(v);
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private void audit(Long uid, String conversationId, ReviewSupport.ReviewTaskRef task, String kind) {
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("getReviewDetail").action("get-review-detail")
                .businessObject((task.docLedger() ? task.ledgerHeader().path("docNumber").asText("") : "plain-task")
                        + "/task:" + task.taskId())
                .success(true)
                .detail("kind=" + kind + ", special=" + task.isSpecial())
                .build());
    }
}

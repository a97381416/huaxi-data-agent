package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 任务详情聚合工具（M-E1，读）：一次调用拼出「驳回意见 + 被驳文件逐条清单 + 可改范围」。
 *
 * <p>聚合三个主后端接口（BFF 在工具层，D1）：
 * <ol>
 *   <li>todo/done-page 扫描定位任务（归属校验 + businessId/instanceId）</li>
 *   <li>GET /doc/ledger/get：台账头（批次号/项目/阶段/状态）+ 条目逐行（live 行，审核语义）</li>
 *   <li>GET /task-center/task/list-by-instance：审批轨迹（谁/何时/意见）</li>
 * </ol>
 * 展示口径：live 行为审核反馈基准（auditStatus/pmOpinion 有效），有 STAGE 暂存的行标 📝；
 * 🔒 已通过锁定行不可改。缺/被驳文件直接在对话里列出（正文条目表已含取得/审核状态）。
 */
@Slf4j
@Component
public class GetTaskDetailTool {

    private final RestActionClient restActionClient;
    private final TaskLedgerSupport support;
    private final ActionAuditLogger auditLogger;

    public GetTaskDetailTool(RestActionClient restActionClient,
                             TaskLedgerSupport support,
                             ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "getTaskDetail",
            description = "查看一条任务的处理详情：台账批次号、项目/子阶段、台账状态、文件条目逐条清单" +
                    "（类别、文件、取得状态、审核状态、PM意见、暂存标记）、审批轨迹（谁在何时驳回/通过及意见）。" +
                    "适用：『XX 任务里哪条被驳了』『PM 给了什么意见』『我要改哪些』。" +
                    "taskId 来自 listMyTasks 返回的任务ID（字符串，原样传入）。")
    public String getTaskDetail(
            @ToolParam(description = "任务ID（来自 listMyTasks 返回，字符串原样传入）", required = true)
            String taskId,
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
            TaskLedgerSupport.TaskRef task = support.findMyTask(taskId, token, tenant);
            if (task == null) {
                return "❌ 未在您的待办/已办中找到该任务（任务 ID 无效、已办结或不属于您）。请先用 listMyTasks 确认任务 ID。";
            }
            TaskLedgerSupport.LedgerView view = support.loadLedgerView(task.ledgerId(), token, tenant);

            StringBuilder sb = new StringBuilder();
            sb.append("## 任务详情（任务ID `").append(task.taskId()).append("`）\n");
            sb.append("- 标题：").append(task.title()).append('\n');
            sb.append("- 台账批次：**").append(view.docNumber()).append("**（")
                    .append(DocLabels.ledgerStatus(view.status())).append("）\n");
            sb.append("- 项目/阶段：").append(view.projectName());
            if (!view.stageName().isBlank()) {
                sb.append(" / ").append(view.stageName());
            }
            sb.append(" / ").append(view.phaseName()).append('\n');
            sb.append("- 流程实例：`").append(view.currentInstanceId()).append("`（提交后会沿原实例推进，不新建流程）\n");
            if (view.hasStage()) {
                sb.append("- 📝 该台账存在暂存修改（").append(view.stageRows().size())
                        .append(" 条，下方标 📝 的行以暂存版参与重提）\n");
            }

            // 条目表（live 行：审核语义基准）+ 暂存标记
            Set<String> stagedIds = new HashSet<>();
            for (ObjectNode row : view.stageRows()) {
                stagedIds.add(row.path("id").asText(""));
            }
            sb.append("\n### 文件条目（").append(view.liveRows().size()).append(" 条；🔒=已通过锁定行不可改，📝=有暂存修改）\n");
            sb.append("| # | 节点 | 类别 | 文件 | 取得状态 | 审核状态 | PM意见 |\n");
            sb.append("|---|------|------|------|----------|----------|--------|\n");
            int i = 0;
            for (ObjectNode row : view.liveRows()) {
                i++;
                String node = view.nodeNames().getOrDefault(row.path("nodeId").asText(""), "未分组");
                String lock = view.lockedIds().contains(row.path("id").asText("")) ? "🔒 " : "";
                String mark = stagedIds.contains(row.path("id").asText("")) ? " 📝" : "";
                sb.append("| ").append(i).append(" | ").append(node).append(" | ")
                        .append(lock).append(row.path("category").asText("")).append(mark).append(" | ")
                        .append(orDash(row.path("fileName").asText(""))).append(" | ")
                        .append(DocLabels.notApplicable(TaskIds.intOrNull(row.path("notApplicable")))).append(" | ")
                        .append(DocLabels.auditStatus(TaskIds.intOrNull(row.path("auditStatus")))).append(" | ")
                        .append(orDash(row.path("pmOpinion").asText(""))).append(" |\n");
            }

            // 审批轨迹（hi_task 历史轮）
            appendTrace(sb, task.instanceId(), token, tenant);

            // 处理指引
            sb.append("\n### 修改指引\n");
            sb.append("- 可修改字段：取得状态 notApplicable（-1待上传/0已取得/1空缺/2不涉及/3其他）、summary 摘要、")
                    .append("description 说明、fileUrl+fileName（换文件）、version、provider、providerTime、docPeriod。\n");
            sb.append("- 修改走增量 patch：说「把 XX 改成 XX」即可；暂存用 stageDraft（低危直接执行），")
                    .append("重提用 resubmitLedger（高危，出确认卡）。已通过行（🔒）与 PM 意见等字段不可改。\n");

            // 缺/被驳文件不再发上传槽位卡：正文条目表已含状态，待补清单由助手在对话里直接列出

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("getTaskDetail").action("get-task-detail")
                    .businessObject(view.docNumber() + "/task:" + task.taskId())
                    .success(true)
                    .detail("entries=" + view.liveRows().size() + ", status=" + view.status())
                    .build());
            return sb.toString();
        } catch (TaskLedgerSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** 审批轨迹：list-by-instance（hi_task 历史轮：谁/何时/结论/意见） */
    private void appendTrace(StringBuilder sb, String instanceId, String token, Long tenantId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/task-center/task/list-by-instance",
                Map.of("instanceId", instanceId), token, tenantId);
        if (!r.success() || !r.data().isArray() || r.data().isEmpty()) {
            return;
        }
        sb.append("\n### 审批轨迹\n");
        for (JsonNode log : r.data()) {
            sb.append("- ").append(log.path("endTime").asText(log.path("createTime").asText("")))
                    .append("　").append(log.path("assigneeName").asText(""))
                    .append("：**").append(DocLabels.traceApproveStatus(TaskIds.intOrNull(log.path("approveStatus"))))
                    .append("**");
            String opinion = log.path("opinion").asText("");
            if (!opinion.isBlank()) {
                sb.append("——「").append(opinion).append("」");
            }
            sb.append('\n');
        }
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}

package com.llmetor.dataagent.dataagent.action.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 我的任务列表工具（M-E1，读）：待办/已办分页 + 台账信息富化。
 *
 * <p>todo/done-page 只返回任务基础信息（批次号在台账详情里），本工具逐条富化
 * GET /doc/ledger/get（docNumber/台账状态/被驳条数，cap 10 条），一次回答
 * 「我有哪些被驳回的任务、各是什么状态」。
 *
 * <p>任务归属由主后端按 assignee 过滤（越权天然不可见）；taskId 一律字符串返回。
 */
@Slf4j
@Component
public class ListMyTasksTool {

    /** 富化上限：超出的条目只列基础信息（防长列表 N+1 放大） */
    private static final int ENRICH_LIMIT = 10;

    private final RestActionClient restActionClient;
    private final TaskLedgerSupport support;
    private final ActionAuditLogger auditLogger;

    public ListMyTasksTool(RestActionClient restActionClient,
                           TaskLedgerSupport support,
                           ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listMyTasks",
            description = "查询当前用户本人的任务列表（资料申报审核任务：被驳回待重提、审批中等）。" +
                    "适用：『我有哪些任务/待办』『哪些被驳回了』『我的已办任务』。" +
                    "返回：任务ID（字符串）、标题（含项目/阶段）、批次号、台账状态、被驳条目数。" +
                    "拿到任务ID后用 getTaskDetail(taskId) 查看被驳明细与 PM 意见。")
    public String listMyTasks(
            @ToolParam(description = "todo=待办任务（默认），done=已办任务", required = false)
            String status,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = TaskIds.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = TaskIds.parseLong(tenantId);
        boolean todo = !"done".equalsIgnoreCase(status == null ? "" : status.trim());

        try {
            RestActionClient.ActionResult r = restActionClient.get(
                    "/task-center/task/" + (todo ? "todo-page" : "done-page"),
                    TaskLedgerSupport.query("pageNo", "1", "pageSize", "100"), token, tenant);
            support.requireOk(r, "查询任务列表");

            StringBuilder sb = new StringBuilder();
            JsonNode list = r.data().path("list");
            long total = r.data().path("total").asLong(list.size());
            sb.append("您的").append(todo ? "待办" : "已办").append("任务共 ").append(total).append(" 条");
            if (list.isEmpty()) {
                sb.append("，当前没有").append(todo ? "待办" : "已办").append("任务。");
                return sb.toString();
            }
            sb.append("（本页展示 ").append(list.size()).append(" 条）：\n");

            int enriched = 0;
            int idx = 0;
            for (JsonNode t : list) {
                idx++;
                String taskId = t.path("id").asText("");
                sb.append(idx).append(". 任务ID `").append(taskId).append("`｜")
                        .append(t.path("title").asText("（无标题）")).append('\n');

                Integer approveStatus = t.path("approveStatus").isNumber() ? t.path("approveStatus").asInt() : null;
                boolean partial = t.path("partialFlag").asBoolean(false);
                String roundLabel = "";
                if (approveStatus != null) {
                    String label = partial ? "部分驳回" : DocLabels.todoApproveStatus(approveStatus);
                    if (!label.isEmpty()) {
                        roundLabel = "（" + label + "）";
                    }
                }

                if (enriched < ENRICH_LIMIT) {
                    enriched++;
                    String ledgerId = t.path("businessId").asText("");
                    String line = enrichLine(ledgerId, token, tenant);
                    if (line != null) {
                        sb.append("   批次号 ").append(line).append(roundLabel).append('\n');
                        continue;
                    }
                }
                sb.append("   任务类型：").append(t.path("categoryLabel").asText(""))
                        .append("｜创建 ").append(t.path("createTime").asText("")).append(roundLabel).append('\n');
            }
            sb.append('\n').append("下一步：用 getTaskDetail(taskId) 查看某条任务的被驳明细、PM 意见与可修改项。");

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listMyTasks").action(todo ? "list-todo" : "list-done")
                    .businessObject("task-page")
                    .success(true)
                    .detail("total=" + total)
                    .build());
            return sb.toString();
        } catch (TaskLedgerSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** 富化行：docNumber｜台账状态｜被驳条数；失败返回 null（回退基础信息行） */
    private String enrichLine(String ledgerId, String token, Long tenantId) {
        if (ledgerId == null || ledgerId.isBlank()) {
            return null;
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get", Map.of("id", ledgerId), token, tenantId);
        if (!r.success()) {
            return null;
        }
        JsonNode data = r.data();
        int rejected = 0;
        int totalEntries = 0;
        for (JsonNode group : data.path("nodeGroups")) {
            for (JsonNode entry : group.path("fileEntries")) {
                totalEntries++;
                if (entry.path("auditStatus").asInt(-1) == 3) {
                    rejected++;
                }
            }
        }
        return data.path("docNumber").asText("") + "｜台账状态：" + DocLabels.ledgerStatus(TaskIds.intOrNull(data.path("status")))
                + "｜条目 " + totalEntries + " 条，被驳 " + rejected + " 条";
    }
}

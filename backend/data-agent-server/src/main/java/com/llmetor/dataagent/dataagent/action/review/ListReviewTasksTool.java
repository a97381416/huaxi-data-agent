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
 * 我的审核任务列表工具（M-E3，读）：审核人视角的待办/已办分页 + 台账富化。
 *
 * <p>todo/done-page 按 assignee 过滤（越权天然不可见）；逐条富化 GET /doc/ledger/get
 * （特殊流程标记/台账状态，cap 10 条），一次回答「我有什么要审、哪些是特殊流程」。
 * nodeType=submitter 的待办是申报人"重新填写"任务，提示切申报技能处理。
 */
@Slf4j
@Component
public class ListReviewTasksTool {

    /** 富化上限：超出的条目只列基础信息（防长列表 N+1 放大） */
    private static final int ENRICH_LIMIT = 10;

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ActionAuditLogger auditLogger;

    public ListReviewTasksTool(RestActionClient restActionClient,
                               ReviewSupport support,
                               ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listReviewTasks",
            description = "查询当前用户名下的审核任务列表（我要审的：待审核任务、已办结审核）。" +
                    "适用：『有什么要审的』『我的待审/已办』『哪些是特殊流程』。" +
                    "返回：任务ID（字符串）、标题（含项目/阶段）、任务类别、节点类型（approval=审核任务 / " +
                    "submitter=重新填写任务）、流程类型（常规/特殊）、台账状态。" +
                    "拿到任务ID后用 getReviewDetail(taskId) 查看待审明细与流程配置。")
    public String listReviewTasks(
            @ToolParam(description = "todo=待审任务（默认），done=已办审核", required = false)
            String status,
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false) String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false) String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false) String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false) String conversationId) {

        Long uid = ReviewSupport.parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }
        Long tenant = ReviewSupport.parseLong(tenantId);
        boolean todo = !"done".equalsIgnoreCase(status == null ? "" : status.trim());

        try {
            RestActionClient.ActionResult r = restActionClient.get(
                    "/task-center/task/" + (todo ? "todo-page" : "done-page"),
                    ReviewSupport.query("pageNo", "1", "pageSize", "100"), token, tenant);
            support.requireOk(r, "查询审核任务列表");

            StringBuilder sb = new StringBuilder();
            JsonNode list = r.data().path("list");
            long total = r.data().path("total").asLong(list.size());
            sb.append("您名下").append(todo ? "待审" : "已办").append("任务共 ").append(total).append(" 条");
            if (list.isEmpty()) {
                sb.append("，当前没有").append(todo ? "待审" : "已办").append("任务。");
                return sb.toString();
            }
            sb.append("（本页展示 ").append(list.size()).append(" 条）：\n");

            int enriched = 0;
            int idx = 0;
            for (JsonNode t : list) {
                idx++;
                String taskId = t.path("id").asText("");
                boolean submitter = "submitter".equals(t.path("nodeType").asText(""));
                sb.append(idx).append(". 任务ID `").append(taskId).append("`｜")
                        .append(t.path("title").asText("（无标题）"));
                if (submitter) {
                    sb.append("｜**重新填写任务**（申报人重提节点，用申报技能处理）");
                }
                Integer approveStatus = t.path("approveStatus").isNumber() ? t.path("approveStatus").asInt() : null;
                if (approveStatus != null && !todo) {
                    String label = DocLabels.todoApproveStatus(approveStatus);
                    if (!label.isEmpty()) {
                        sb.append("｜结论：").append(label);
                    }
                }
                sb.append('\n');

                sb.append("   类别：").append(t.path("categoryLabel").asText(""));
                String created = t.path("createTime").asText("");
                if (!created.isBlank()) {
                    sb.append("｜创建 ").append(created);
                }
                if (todo && enriched < ENRICH_LIMIT) {
                    enriched++;
                    String flow = enrichFlow(t.path("businessId").asText(""), token, tenant);
                    if (flow != null) {
                        sb.append("｜").append(flow);
                    }
                }
                sb.append('\n');
            }
            sb.append('\n').append("下一步：用 getReviewDetail(taskId) 查看某条任务的待审条目、流程类型（常规/特殊）与审批链。");

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listReviewTasks").action(todo ? "list-todo" : "list-done")
                    .businessObject("task-page")
                    .success(true)
                    .detail("total=" + total)
                    .build());
            return sb.toString();
        } catch (ReviewSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** 富化行：常规/特殊流程｜台账状态；失败返回 null（回退基础信息行） */
    private String enrichFlow(String ledgerId, String token, Long tenantId) {
        if (ledgerId == null || ledgerId.isBlank()) {
            return null;
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/doc/ledger/get", Map.of("id", ledgerId), token, tenantId);
        if (!r.success()) {
            return null;
        }
        JsonNode data = r.data();
        JsonNode special = data.path("isSpecial");
        boolean isSpecial = special.isBoolean() ? special.asBoolean() : special.asInt(0) == 1;
        return (isSpecial ? "**特殊流程**（通过后需安排下级协同审核）" : "常规流程")
                + "｜台账：" + DocLabels.ledgerStatus(ReviewSupport.intOrNull(data.path("status")));
    }
}

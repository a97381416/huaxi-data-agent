package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 协同审核选人工具（M-E3，读）：特殊流程通过时"选下一级审批人"的选项数据。
 *
 * <p>对齐 PC NodeConfigSection 数据源：GET /doc/recommend/assignees（按台账项目/子阶段
 * 历史推荐，category 固定 doc_collect）+ GET /system/dept/simple-list（部门树）+
 * GET /system/user/page-for-ignore-auth（按部门查人 / 按姓名全局反查）。
 * 项目/子阶段上下文从任务代查（taskId → 台账头），不让 LLM 传 projectId。
 */
@Slf4j
@Component
public class ListCoauditOptionsTool {

    private final RestActionClient restActionClient;
    private final ReviewSupport support;
    private final ActionAuditLogger auditLogger;

    public ListCoauditOptionsTool(RestActionClient restActionClient,
                                  ReviewSupport support,
                                  ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.support = support;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listCoauditOptions",
            description = "特殊流程审核通过时【选下一级审批人】的辅助（三级选人协议）：①传 taskId → 返回常用审批人推荐 + 部门清单；" +
                    "②用户点名姓名 → 传 userNameKeyword 全局按姓名反查人员ID（唯一命中可直接当下级审批人）；" +
                    "③按部门选人 → 传 deptId 查该部门人员（部门ID来自①返回的部门清单）。" +
                    "②姓名反查模式独立调用、无需 taskId。查到的用户ID 传给 approveTask 的 nextApproverIds。")
    public String listCoauditOptions(
            @ToolParam(description = "审核任务ID（①常用推荐模式必传，字符串原样传入；②姓名反查模式不用传）", required = false)
            String taskId,
            @ToolParam(description = "可选：人员姓名关键字（全局模糊反查用户ID；唯一命中可直接作为下级审批人）", required = false)
            String userNameKeyword,
            @ToolParam(description = "可选部门ID：传入时返回该部门人员列表（来自①返回的部门清单ID）", required = false)
            String deptId,
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
            // ② 姓名反查模式：全局按昵称模糊匹配（不依赖任务上下文，可叠加部门过滤）
            if (userNameKeyword != null && !userNameKeyword.isBlank()) {
                return reverseLookupUsers(userNameKeyword.trim(), deptId, uid, token, tenant, conversationId);
            }
            if (taskId == null || taskId.isBlank()) {
                return "❌ 查询常用审批人推荐需要 taskId。若只想反查人员请传 userNameKeyword；按部门查人传 deptId。";
            }
            ReviewSupport.ReviewTaskRef task = support.findMyReviewTask(taskId, token, tenant);
            if (task == null || task.ledgerHeader() == null) {
                return "❌ 未找到该任务或任务无关联台账，无法定位项目上下文。请先用 listReviewTasks/getReviewDetail 确认任务。";
            }
            String projectId = task.ledgerHeader().path("projectId").asText("");
            String phaseId = task.ledgerHeader().path("phaseId").asText("");
            StringBuilder sb = new StringBuilder("## 下一级审批人选项（任务 `").append(task.taskId()).append("`）\n");

            // ① 常用审批人推荐（按台账项目/子阶段历史）
            sb.append("\n### 常用审批人（按本项目/子阶段历史推荐）\n");
            if (projectId.isEmpty() || phaseId.isEmpty()) {
                sb.append("- （台账缺少项目/子阶段上下文，请按部门选择）\n");
            } else {
                RestActionClient.ActionResult r = restActionClient.get(
                        "/doc/recommend/assignees",
                        ReviewSupport.query("projectId", projectId, "subStageId", phaseId,
                                "limit", "8", "category", "doc_collect"), token, tenant);
                int cnt = 0;
                if (r.success() && r.data().isArray()) {
                    for (JsonNode a : r.data()) {
                        cnt++;
                        sb.append("- ").append(a.path("nickname").asText(""))
                                .append("（").append(a.path("deptName").asText("—"))
                                .append("，被选 ").append(a.path("selectedCount").asInt(0))
                                .append(" 次/实审 ").append(a.path("actedCount").asInt(0)).append(" 次）ID:")
                                .append(a.path("userId").asText()).append('\n');
                    }
                }
                if (cnt == 0) {
                    sb.append("- （暂无历史推荐，请按部门选择）\n");
                }
            }

            // 部门清单
            sb.append("\n### 部门（按部门选人）\n");
            RestActionClient.ActionResult dr = restActionClient.get(
                    "/system/dept/simple-list", null, token, tenant);
            support.requireOk(dr, "查询部门列表");
            int deptCnt = 0;
            for (JsonNode d : dr.data()) {
                if (deptCnt++ < 30) {
                    sb.append("- ").append(d.path("name").asText()).append("（ID:").append(d.path("id").asText()).append("）\n");
                }
            }
            if (deptCnt > 30) {
                sb.append("…等共 ").append(deptCnt).append(" 个部门\n");
            }

            // ③ 按部门查人员
            if (deptId != null && !deptId.isBlank()) {
                sb.append("\n### 部门人员（deptId=").append(deptId.trim()).append("）\n");
                JsonNode listNode = pageUsers(null, deptId.trim(), token, tenant);
                int userCnt = 0;
                for (JsonNode u : listNode) {
                    userCnt++;
                    sb.append("- ").append(u.path("nickname").asText("")).append("（ID:")
                            .append(u.path("id").asText()).append("）\n");
                }
                if (userCnt == 0) {
                    sb.append("- （该部门暂无人员）\n");
                }
            }
            sb.append("\n下一级固定为**部门会签**（所选人员全部通过后流程继续）；")
                    .append("确定人选后把用户ID列表传给 approveTask 的 nextApproverIds。");
            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listCoauditOptions").action("list")
                    .businessObject("task:" + task.taskId())
                    .success(true)
                    .build());
            return sb.toString();
        } catch (ReviewSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /** ② 姓名全局反查：唯一命中 → 可直接使用；多命中 → 候选清单；无命中 → 引导 */
    private String reverseLookupUsers(String keyword, String deptId, Long uid,
                                      String token, Long tenant, String conversationId) {
        JsonNode list = pageUsers(keyword, (deptId == null || deptId.isBlank()) ? null : deptId.trim(), token, tenant);
        List<String> labels = new ArrayList<>();
        for (JsonNode u : list) {
            String id = u.path("id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            String dept = u.path("deptName").asText("");
            labels.add(u.path("nickname").asText("") + "(ID:" + id
                    + (dept.isEmpty() ? "" : "，" + dept) + ")");
        }

        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId).userId(uid)
                .tool("listCoauditOptions").action("lookup")
                .businessObject("user:" + keyword + (deptId != null && !deptId.isBlank() ? "/dept:" + deptId.trim() : "")
                        + "/hits:" + labels.size())
                .success(true)
                .build());

        if (labels.size() == 1) {
            return "✅ 唯一命中：" + labels.get(0)
                    + "\n可直接作为下级审批人：把该 ID 传入 approveTask 的 nextApproverIds。";
        }
        if (labels.isEmpty()) {
            return "❌ 未找到姓名含「" + keyword + "」的人员。可尝试：用完整姓名重试；" +
                    "或调本工具（不带 userNameKeyword，带 taskId）查看部门清单后传 deptId 按部门选人。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(labels.size())
                .append(" 人姓名含「").append(keyword).append("」，请让用户指认：\n");
        for (String label : labels) {
            sb.append("- ").append(label).append('\n');
        }
        sb.append("确定后以对应 ID 作为 nextApproverIds 重新发起 approveTask 首轮。");
        return sb.toString();
    }

    /** page-for-ignore-auth 包装：nickname（姓名关键字，可空）+ deptId（部门，可空） */
    private JsonNode pageUsers(String nickname, String deptId, String token, Long tenantId) {
        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
        if (nickname != null && !nickname.isBlank()) {
            params.put("nickname", nickname.trim());
        }
        if (deptId != null && !deptId.isBlank()) {
            params.put("deptId", deptId.trim());
        }
        params.put("pageNo", "1");
        params.put("pageSize", "20");
        RestActionClient.ActionResult r = restActionClient.get(
                "/system/user/page-for-ignore-auth", params, token, tenantId);
        support.requireOk(r, "查询人员");
        JsonNode list = r.data().path("list");
        if (list.isMissingNode() || !list.isArray()) {
            list = r.data();
        }
        return list;
    }
}

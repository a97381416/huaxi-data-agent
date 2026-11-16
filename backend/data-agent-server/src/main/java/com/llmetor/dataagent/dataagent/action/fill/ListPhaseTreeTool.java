package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 项目阶段树工具（M-E2，读）：填报第 2 步「选阶段」的数据源。
 *
 * <p>聚合 GET /doc/project/detail（大阶段四态统计）+ 每个大阶段
 * GET /doc/project/phase-children（子阶段树），返回文本树，交互全走对话。
 */
@Slf4j
@Component
public class ListPhaseTreeTool {

    private final RestActionClient restActionClient;
    private final FillContextSupport contextSupport;
    private final ActionAuditLogger auditLogger;

    public ListPhaseTreeTool(RestActionClient restActionClient,
                             FillContextSupport contextSupport,
                             ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.contextSupport = contextSupport;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listPhaseTree",
            description = "查看一个项目的填报阶段树：大阶段横向进度（已传/总数/空缺/不涉及）+ 各大阶段下的子阶段清单。" +
                    "用于资料填报第二步选阶段；用户可说大阶段名（返回其子阶段候选）或直接点名子阶段。" +
                    "projectIdOrName 来自 listMyProjects 或用户点名。")
    public String listPhaseTree(
            @ToolParam(description = "项目ID/名称/编码（来自 listMyProjects 或用户点名）", required = true)
            String projectIdOrName,
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
            FillContextSupport.ProjectRef project = contextSupport.findProject(projectIdOrName, token, tenant);
            RestActionClient.ActionResult r = restActionClient.get(
                    "/doc/project/detail",
                    FillContextSupport.query("projectId", project.projectId()), token, tenant);
            requireOk(r, "查询项目阶段树");

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listPhaseTree").action("list")
                    .businessObject(project.projectName())
                    .success(true)
                    .build());

            StringBuilder sb = new StringBuilder("## ").append(project.projectName())
                    .append(" · 填报阶段树\n\n### 大阶段\n");
            for (JsonNode major : r.data().path("majorPhases")) {
                String majorId = major.path("phaseId").asText("");
                String majorName = major.path("phaseName").asText("");
                int uploaded = major.path("uploadedCount").asInt(0);
                int total = major.path("entryTotal").asInt(0);
                int missing = major.path("missingCount").asInt(0);
                int notInvolved = major.path("notInvolvedCount").asInt(0);

                sb.append("- **").append(majorName).append("**：已传 ").append(uploaded).append("/").append(total);
                if (missing > 0) {
                    sb.append("，空缺 ").append(missing);
                }
                if (notInvolved > 0) {
                    sb.append("，不涉及 ").append(notInvolved);
                }
                sb.append('\n');

                RestActionClient.ActionResult cr = restActionClient.get(
                        "/doc/project/phase-children",
                        FillContextSupport.query("projectId", project.projectId(), "majorPhaseId", majorId),
                        token, tenant);
                if (cr.success()) {
                    for (JsonNode sub : cr.data().path("list")) {
                        sb.append("  - ").append(sub.path("name").asText(""))
                                .append("（编码 ").append(sub.path("code").asText()).append("）：已传 ")
                                .append(sub.path("uploadedCount").asInt(0)).append("/")
                                .append(sub.path("entryTotal").asInt(0));
                        int m = sub.path("missingCount").asInt(0);
                        if (m > 0) {
                            sb.append("，空缺 ").append(m);
                        }
                        sb.append('\n');
                    }
                }
            }

            sb.append("\n请选择要填报的子阶段（说「子阶段名/编码」，或先说大阶段名我来列其子阶段）。");
            return sb.toString();
        } catch (FillContextSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    private void requireOk(RestActionClient.ActionResult r, String context) {
        if (!r.success()) {
            throw new FillContextSupport.TaskFlowException("❌ " + context + "失败：" + r.msg());
        }
    }
}

package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 提审选项工具（M-E2，读）：提审配置首屏数据（交互全走对话）。
 *
 * <p>聚合 GET /doc/recommend/assignees（按子阶段历史推荐审核人，category 固定 doc_collect）+
 * GET /system/dept/simple-list（部门树）；deptId 传入时再查
 * GET /system/user/page-for-ignore-auth（该部门人员，含兼职部门）。
 *
 * <p>主平台该接口 projectId/subStageId 均为 Long：项目/子阶段先经
 * {@link FillContextSupport#findProject}/{@link FillContextSupport#resolvePhase} 定位，
 * 不让 LLM 原样透传名称（曾把项目名"南三高速公路项目"当 projectId 传 400）。
 */
@Slf4j
@Component
public class ListReviewOptionsTool {

    private final RestActionClient restActionClient;
    private final ActionAuditLogger auditLogger;
    private final FillContextSupport contextSupport;

    public ListReviewOptionsTool(RestActionClient restActionClient,
                                 ActionAuditLogger auditLogger,
                                 FillContextSupport contextSupport) {
        this.restActionClient = restActionClient;
        this.auditLogger = auditLogger;
        this.contextSupport = contextSupport;
    }

    @Tool(name = "listReviewOptions",
            description = "提审/重提的选人辅助（三级选人协议）：①查常用审核人推荐→不传参（返回常用Top1默认+部门列表）；" +
                    "②用户点名姓名→传 userNameKeyword 全局按姓名反查人员ID（唯一命中可直接当审核人）；" +
                    "③按部门选人→传 deptId 查该部门人员（部门ID来自不传参时的部门清单）。" +
                    "projectIdOrName/subStageIdOrNameOrCode 仅①模式需要；userNameKeyword 模式独立调用、无需项目上下文。")
    public String listReviewOptions(
            @ToolParam(description = "项目名称或项目ID（①常用推荐模式必传；②姓名反查/③部门人员模式不用传）", required = false)
            String projectIdOrName,
            @ToolParam(description = "子阶段ID/编码/名称（①常用推荐模式必传；②③模式不用传）", required = false)
            String subStageIdOrNameOrCode,
            @ToolParam(description = "可选：人员姓名关键字（全局模糊反查用户ID；唯一命中可直接作为审核人）", required = false)
            String userNameKeyword,
            @ToolParam(description = "可选部门ID：传入时返回该部门人员列表（来自部门列表返回的 ID）", required = false)
            String deptId,
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
            // ② 姓名反查模式：全局按昵称模糊匹配（不依赖项目/阶段上下文，可叠加部门过滤）
            if (userNameKeyword != null && !userNameKeyword.isBlank()) {
                return reverseLookupUsers(userNameKeyword.trim(), deptId, uid, token, tenant, conversationId);
            }
            // ①③ 模式需要项目/阶段上下文
            if (isBlank(projectIdOrName) || isBlank(subStageIdOrNameOrCode)) {
                return "❌ 查询常用审核人推荐需要 projectIdOrName + subStageIdOrNameOrCode。" +
                        "若只想反查人员请传 userNameKeyword；按部门查人传 deptId。";
            }
            // 项目定位：名称/ID/编码 → my-projects 唯一命中（未命中附候选清单）
            FillContextSupport.ProjectRef project =
                    contextSupport.findProject(projectIdOrName, token, tenant);
            // 子阶段定位：数字串按 ID 直用，名称/编码走阶段树/resolve-phase
            String subStageId;
            String subStageLabel;
            Long subStageNum = FillContextSupport.parseLong(subStageIdOrNameOrCode);
            if (subStageNum != null) {
                subStageId = String.valueOf(subStageNum);
                subStageLabel = "phase:" + subStageId;
            } else {
                FillContextSupport.PhaseTarget phase =
                        contextSupport.resolvePhase(project, subStageIdOrNameOrCode, token, tenant);
                subStageId = phase.phaseId();
                subStageLabel = "phase:" + subStageId + "(" + phase.phaseName() + ")";
            }

            // 常用审核人推荐（按子阶段聚合）；category 必传：主平台 SQL 对 null category
            // 走 #{category} IS NULL，PostgreSQL 下无法推断参数类型直接 500（系统异常）
            RestActionClient.ActionResult r = restActionClient.get(
                    "/doc/recommend/assignees",
                    FillContextSupport.query("projectId", project.projectId(),
                            "subStageId", subStageId, "limit", "8",
                            "category", "doc_collect"), token, tenant);
            requireOk(r, "查询常用审核人");

            // 部门树（首屏）
            RestActionClient.ActionResult dr = restActionClient.get(
                    "/system/dept/simple-list", null, token, tenant);
            requireOk(dr, "查询部门列表");

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listReviewOptions").action("list")
                    .businessObject("project:" + project.projectId() + "(" + project.projectName()
                            + ")/" + subStageLabel)
                    .success(true)
                    .build());

            int recommendCnt = 0;
            StringBuilder sb = new StringBuilder("## 提审配置选项（项目：")
                    .append(project.projectName()).append("）\n\n### 常用审核人（按本子阶段历史推荐）\n");
            for (JsonNode a : r.data()) {
                recommendCnt++;
                sb.append("- ").append(a.path("nickname").asText(""))
                        .append("（").append(a.path("deptName").asText("—"))
                        .append("，被选 ").append(a.path("selectedCount").asInt(0))
                        .append(" 次/实审 ").append(a.path("actedCount").asInt(0)).append(" 次）ID:")
                        .append(a.path("userId").asText()).append('\n');
            }
            if (recommendCnt == 0) {
                sb.append("- （暂无历史推荐，请按部门选择审核人）\n");
            }

            sb.append("\n### 部门（按部门选人）\n");
            int deptCnt = 0;
            for (JsonNode d : dr.data()) {
                if (deptCnt++ < 30) {
                    sb.append("- ").append(d.path("name").asText()).append("（ID:").append(d.path("id").asText()).append("）\n");
                }
            }
            if (deptCnt > 30) {
                sb.append("…等共 ").append(deptCnt).append(" 个部门\n");
            }

            // 可选：按部门查人员（对话兜底：用户点名部门时列出该部门可选审核人）
            if (deptId != null && !deptId.isBlank()) {
                RestActionClient.ActionResult ur = restActionClient.get(
                        "/system/user/page-for-ignore-auth",
                        FillContextSupport.query("deptId", deptId.trim(), "pageNo", "1", "pageSize", "100"),
                        token, tenant);
                requireOk(ur, "查询部门人员");
                sb.append("\n### 部门人员（deptId=").append(deptId.trim()).append("）\n");
                JsonNode listNode = ur.data().path("list");
                if (listNode.isMissingNode() || !listNode.isArray() || listNode.isEmpty()) {
                    listNode = ur.data();
                }
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

            sb.append("\n请收集：① 提交说明（必填）② 审核人（常用推荐或按部门选择，可多选，会签）。")
                    .append("用户配置完成后，把 projectIdOrName=")
                    .append(project.projectId())
                    .append(" + submitRemark + assigneeIds 传给 submitLedgerForReview 首轮生成提审计划。");
            return sb.toString();
        } catch (FillContextSupport.TaskFlowException e) {
            return e.getMessage();
        }
    }

    /**
     * ② 姓名全局反查：/system/user/page-for-ignore-auth?nickname=（可叠加 deptId）。
     * 唯一命中 → 给可直接使用的审核人 ID；多命中 → 候选清单；无命中 → 引导换关键词或走部门选人。
     */
    private String reverseLookupUsers(String keyword, String deptId, Long uid,
                                      String token, Long tenant, String conversationId) {
        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
        params.put("nickname", keyword);
        params.put("pageNo", "1");
        params.put("pageSize", "20");
        if (deptId != null && !deptId.isBlank()) {
            params.put("deptId", deptId.trim());
        }
        RestActionClient.ActionResult r = restActionClient.get(
                "/system/user/page-for-ignore-auth", params, token, tenant);
        requireOk(r, "按姓名反查人员");
        JsonNode list = r.data().path("list");
        if (list.isMissingNode() || !list.isArray()) {
            list = r.data();
        }
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
                .tool("listReviewOptions").action("lookup")
                .businessObject("user:" + keyword + (deptId != null && !deptId.isBlank() ? "/dept:" + deptId : "")
                        + "/hits:" + labels.size())
                .success(true)
                .build());

        if (labels.size() == 1) {
            return "✅ 唯一命中：" + labels.get(0)
                    + "\n可直接作为审核人：把该 ID 传入 submitLedgerForReview/resubmitLedger 的 assigneeIds。";
        }
        if (labels.isEmpty()) {
            return "❌ 未找到姓名含「" + keyword + "」的人员。可尝试：用完整姓名重试；" +
                    "或调本工具（不带 userNameKeyword）查看部门清单后传 deptId 按部门选人。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(labels.size())
                .append(" 人姓名含「").append(keyword).append("」，请让用户指认：\n");
        for (String label : labels) {
            sb.append("- ").append(label).append('\n');
        }
        sb.append("确定后以对应 ID 作为 assigneeIds 重新发起提审/重提首轮。");
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void requireOk(RestActionClient.ActionResult r, String context) {
        if (!r.success()) {
            throw new FillContextSupport.TaskFlowException("❌ " + context + "失败：" + r.msg());
        }
    }
}

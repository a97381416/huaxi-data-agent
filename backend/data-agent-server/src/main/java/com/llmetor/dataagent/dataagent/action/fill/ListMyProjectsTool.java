package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmetor.dataagent.dataagent.action.ActionAuditLogger;
import com.llmetor.dataagent.dataagent.action.RestActionClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 我的项目列表工具（M-E2，读）：填报第 1 步「选项目」的数据源。
 *
 * <p>GET /doc/workbench/my-projects（pageSize=99，数据权限自动按角色过滤），
 * 返回文本清单（序号+备案完整度+预警），交互全走对话（用户说「第N个」或点名项目）。
 */
@Slf4j
@Component
public class ListMyProjectsTool {

    private final RestActionClient restActionClient;
    private final ActionAuditLogger auditLogger;

    public ListMyProjectsTool(RestActionClient restActionClient,
                              ActionAuditLogger auditLogger) {
        this.restActionClient = restActionClient;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "listMyProjects",
            description = "列出我名下进行中的项目（含文件备案完整度、预警、当前大阶段），用于资料填报第一步选项目。" +
                    "返回带序号清单；用户可说「第N个」或点名项目。keyword 可选，按项目名/编码过滤。")
    public String listMyProjects(
            @ToolParam(description = "可选关键词，按项目名称/编码模糊过滤", required = false)
            String keyword,
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
            RestActionClient.ActionResult r = restActionClient.get(
                    "/doc/workbench/my-projects", FillContextSupport.query("pageSize", "99"), token, tenant);
            requireOk(r, "查询我的项目");

            auditLogger.log(ActionAuditLogger.entry()
                    .conversationId(conversationId).userId(uid)
                    .tool("listMyProjects").action("list")
                    .businessObject("my-projects")
                    .success(true)
                    .detail("keyword=" + (keyword == null ? "" : keyword))
                    .build());

            StringBuilder sb = new StringBuilder("## 我的项目（进行中）\n");
            int shown = 0;
            String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            for (JsonNode p : r.data().path("list")) {
                if (!(p instanceof ObjectNode o)) {
                    continue;
                }
                String name = o.path("projectName").asText("");
                if (!kw.isEmpty()
                        && !name.toLowerCase(Locale.ROOT).contains(kw)
                        && !o.path("projectCode").asText("").toLowerCase(Locale.ROOT).contains(kw)) {
                    continue;
                }
                int uploaded = 0;
                int total = 0;
                for (JsonNode ph : o.path("phases")) {
                    uploaded += ph.path("uploadedCount").asInt(0);
                    total += ph.path("entryTotal").asInt(0);
                }

                sb.append(++shown).append(". **").append(name).append("**（编码 ")
                        .append(o.path("projectCode").asText(""));
                String type = o.path("type").asText("");
                if (!type.isBlank()) {
                    sb.append("，").append(type);
                }
                if (total > 0) {
                    sb.append("，备案 ").append(uploaded).append("/").append(total);
                }
                if (o.path("hasWarning").asBoolean(false)) {
                    sb.append("，⚠ 有预警");
                }
                sb.append("）\n");
            }
            if (shown == 0) {
                return "未在您的数据范围内找到进行中的项目，无法填报资料。";
            }

            sb.append("\n请选择要填报的项目（说「第N个」或点名项目名/编码均可）。");
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

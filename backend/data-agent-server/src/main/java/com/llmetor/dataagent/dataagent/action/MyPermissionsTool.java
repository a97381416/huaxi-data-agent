package com.llmetor.dataagent.dataagent.action;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * demo 工具（M-E0 验收）：查询当前用户在主平台的功能权限清单。
 *
 * <p>端到端验证写通道三件事：
 * <ol>
 *   <li>REST 转发：以当前用户 token + tenant-id 调主后端
 *       {@code GET /system/auth/get-permission-info}（真实过 yudao 鉴权链）</li>
 *   <li>功能权限校验：{@link FunctionPermissionChecker} 本地侧 system_role_menu 查询</li>
 *   <li>动作审计：{@link ActionAuditLogger} 结构化落库</li>
 * </ol>
 *
 * <p>输出为 DB 侧权限清单（主后端不可达时仍可用）+ REST 通道状态行（联调观测点）。
 * userId/token/tenantId/conversationId 均由内核 toolParams 强制注入，不依赖 LLM 传参。
 */
@Slf4j
@Component
public class MyPermissionsTool {

    private final FunctionPermissionChecker permissionChecker;
    private final RestActionClient restActionClient;
    private final ActionAuditLogger auditLogger;

    public MyPermissionsTool(FunctionPermissionChecker permissionChecker,
                             RestActionClient restActionClient,
                             ActionAuditLogger auditLogger) {
        this.permissionChecker = permissionChecker;
        this.restActionClient = restActionClient;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "myPermissions",
            description = "查询当前用户本人在主平台拥有的功能权限清单（按钮/操作级，如资料填报、预警处理、项目管理等）。" +
                    "适用：用户问『我有哪些权限』『我能不能做某操作』，或执行业务写操作前确认权限。" +
                    "本工具无需任何参数（身份由系统自动注入），直接调用即可。")
    public String myPermissions(
            @ToolParam(description = "用户身份标识，由框架自动注入真实值", required = false)
            String userId,
            @ToolParam(description = "访问令牌，由框架自动注入真实值", required = false)
            String token,
            @ToolParam(description = "租户标识，由框架自动注入真实值", required = false)
            String tenantId,
            @ToolParam(description = "会话标识，由框架自动注入真实值", required = false)
            String conversationId) {

        Long uid = parseUserId(userId);
        if (uid == null) {
            return "❌ 未获取到当前用户身份，请让用户重新登录后再试。";
        }

        // ① DB 侧权限清单（本侧查询，主后端宕机也可用）
        List<String> permissions = new ArrayList<>(permissionChecker.loadPermissions(uid));

        // ② REST 通道验证：真实转发主后端，过一遍 Bearer + tenant-id 鉴权链
        String channelStatus;
        boolean restOk = false;
        List<String> restPermissions = null;
        if (token == null || token.isBlank()) {
            channelStatus = "⚠ 未携带登录凭证（token），REST 通道未验证——上述清单来自本地权限查询，仍可参考。";
        } else {
            RestActionClient.ActionResult result = restActionClient.get(
                    "/system/auth/get-permission-info", Map.of(), token, parseLong(tenantId));
            restOk = result.success();
            if (restOk) {
                restPermissions = extractPermissions(result.data());
                channelStatus = "✅ 主系统通道正常（REST 转发 + 鉴权验证通过）";
                // 主后端侧清单以 REST 返回为准（同源权威）；DB 侧仅兜底
                if (restPermissions != null) {
                    permissions = restPermissions;
                }
            } else {
                channelStatus = "⚠ 主系统通道暂不可用（" + result.msg() + "）——上述清单来自本地权限查询。";
            }
        }

        // ③ 审计（读工具也记：M-E0 观测链路）
        auditLogger.log(ActionAuditLogger.entry()
                .conversationId(conversationId)
                .userId(uid)
                .tool("myPermissions")
                .action("query-my-permissions")
                .businessObject("permission-list")
                .success(true)
                .detail("dbCount=" + permissions.size() + ", restVerified=" + restOk)
                .build());

        return format(permissions, channelStatus);
    }

    /** 从 get-permission-info 响应 data 中取 permissions 数组 */
    private static List<String> extractPermissions(JsonNode data) {
        if (data == null || !data.has("permissions")) {
            return null;
        }
        List<String> list = new ArrayList<>();
        data.path("permissions").forEach(p -> {
            String v = p.asText(null);
            if (v != null && !v.isBlank()) {
                list.add(v);
            }
        });
        return list;
    }

    private static String format(List<String> permissions, String channelStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前用户的功能权限清单（").append(permissions.size()).append(" 项）：\n");
        if (permissions.isEmpty()) {
            sb.append("-（无任何按钮级操作权限；如需填报/处理预警等操作，请联系管理员开通）\n");
        } else {
            // 排序分组：按权限点前缀（域）分组展示更可读
            new TreeSet<>(permissions).forEach(p -> sb.append("- ").append(p).append('\n'));
        }
        sb.append('\n').append(channelStatus);
        return sb.toString().trim();
    }

    private static Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

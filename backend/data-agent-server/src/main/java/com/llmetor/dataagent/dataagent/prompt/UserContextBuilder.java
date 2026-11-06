package com.llmetor.dataagent.dataagent.prompt;

import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.auth.service.HuaxiAuthService.HuaxiUserInfo;
import com.llmetor.dataagent.dataagent.safety.SensitiveFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 用户上下文构建器（M-H2 华西版）：把"当前登录人是谁、能看多大范围"写进
 * system prompt 尾部——用户信息与数据范围均来自华西 yudao 表
 * （system_users/system_role/system_user_role/system_dept）。
 * 原 sakila 版的个人档案段（user_profile）华西无此表，已移除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextBuilder {

    private final HuaxiAuthService authService;
    private final SensitiveFilter sensitiveFilter;

    @Value("${data-agent.data-permission.enabled:false}")
    private boolean dataPermissionEnabled;

    /** 构造用户上下文 markdown；userId 为空或加载失败返回空串（不阻断执行） */
    public String build(Long userId) {
        if (userId == null) {
            return "";
        }
        HuaxiUserInfo user;
        try {
            user = authService.getUserInfo(userId);
        } catch (Exception e) {
            log.warn("[UserContext] 加载用户失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
        if (user == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n## 当前用户上下文\n\n");
        sb.append("- 用户ID：").append(user.id()).append("\n");
        sb.append("- 用户名：").append(nullSafe(user.username())).append("\n");
        sb.append("- 昵称：").append(nullSafe(user.nickname())).append("\n");
        appendRoles(sb, user);
        appendDept(sb, user);
        appendDataScope(sb, user);
        appendExecutionMode(sb);
        sb.append("\n### 用户问\"我的 xxx\"时的指代\n");
        sb.append("- 指当前用户（ID=").append(user.id()).append("，")
                .append(nullSafe(user.nickname())).append("）参与的数据：项目按 doc_project_member 成员关系，")
                .append("预警按责任人 responsible_user_id，通用数据按 creator。\n");
        return sb.toString();
    }

    private void appendRoles(StringBuilder sb, HuaxiUserInfo user) {
        List<String> roles = user.roles();
        if (roles == null || roles.isEmpty()) {
            sb.append("- 角色：未分配\n");
            return;
        }
        sb.append("- 角色：").append(String.join("、", roles)).append("\n");
    }

    private void appendDept(StringBuilder sb, HuaxiUserInfo user) {
        sb.append("- 主部门：").append(nullSafe(user.deptName()).isEmpty() ? "未挂载" : user.deptName()).append("\n");
    }

    /** 生效数据范围：让 LLM 知道可见范围，范围类问题（"我的部门""全部"）不再靠猜 */
    private void appendDataScope(StringBuilder sb, HuaxiUserInfo user) {
        String scope = nullSafe(user.dataScope()).toUpperCase(Locale.ROOT);
        String desc = switch (scope) {
            case "ALL" -> "ALL——全部数据";
            case "DEPT_AND_SUB" -> "DEPT_AND_SUB——本部门及全部子部门";
            case "DEPT" -> "DEPT——仅本部门（不含子部门）";
            case "CUSTOM" -> "CUSTOM——角色配置的指定部门集合";
            default -> "SELF——仅本人的数据（默认最小范围）";
        };
        sb.append("- 生效数据范围：").append(desc).append("\n");
    }

    private void appendExecutionMode(StringBuilder sb) {
        if (!dataPermissionEnabled && !sensitiveFilter.isEnabled()) {
            return;
        }
        sb.append("\n### 执行模式说明（系统行为，与你的 SQL 写法无关）\n");
        if (dataPermissionEnabled) {
            sb.append("- **数据权限已开启**：SQL 执行前系统会按上面的数据范围自动注入 WHERE/ON ")
                    .append("过滤条件（executeSql 返回的 executedSql 可能包含你未编写的条件，那是权限注入，属正常现象）。")
                    .append("你不需要（也不应该）在 SQL 里手写权限过滤；不要尝试用子查询/JOIN 绕过——绕不过去。\n");
        }
        if (sensitiveFilter.isEnabled()) {
            sb.append("- **敏感字段脱敏已开启**：查询结果里值为 `********` 的单元格是系统脱敏后的展示，")
                    .append("不是数据为空。返回 ******** 是系统行为，不要尝试绕过、反推或猜测真实值；")
                    .append("报告里如实呈现 ******** 即可，必要时向用户说明\"该列已由系统脱敏\"。\n");
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}

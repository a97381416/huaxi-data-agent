package com.llmetor.dataagent.dataagent.prompt;

import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.auth.service.HuaxiAuthService.HuaxiUserInfo;
import com.llmetor.dataagent.dataagent.permission.DataScopeContext;
import com.llmetor.dataagent.dataagent.permission.DataScopeResolver;
import com.llmetor.dataagent.dataagent.safety.SensitiveFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 用户上下文构建器（M-H4 对齐版）：把"当前登录人是谁、能看多大范围"写进
 * system prompt 尾部——用户信息来自华西 yudao 表（system_users/system_role/
 * system_user_role/system_dept），数据范围由 {@link DataScopeResolver} 按
 * 主后端并集口径解析（角色多档 ∪ 兼职部门 ∪ 项目成员）。
 * 上游版的个人档案段（user_profile）华西无此表，已移除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextBuilder {

    private final HuaxiAuthService authService;
    private final DataScopeResolver dataScopeResolver;
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
        appendRoleScopes(sb, user);
        appendDataScope(sb, user);
        appendReferenceRules(sb, user);
        appendExecutionMode(sb);
        return sb.toString();
    }

    /** 逐角色数据范围明细：如 "申报人员（本人、兼职部门）"——用户能看多大范围的判定依据 */
    private void appendRoleScopes(StringBuilder sb, HuaxiUserInfo user) {
        List<String> roleScopes;
        try {
            roleScopes = dataScopeResolver.describeRoleScopes(user.id());
        } catch (Exception e) {
            log.warn("[UserContext] 角色数据范围加载失败 userId={}: {}", user.id(), e.getMessage());
            return;
        }
        if (roleScopes == null || roleScopes.isEmpty()) {
            return;
        }
        sb.append("- 角色数据范围：").append(String.join("；", roleScopes)).append("\n");
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

    /**
     * 生效数据范围：按主后端并集口径如实输出（部门并集含兼职部门 ∪ 本人创建），
     * 让 LLM 知道可见范围，范围类问题（"我能看到哪些项目""全部"）不再靠猜。
     */
    private void appendDataScope(StringBuilder sb, HuaxiUserInfo user) {
        DataScopeContext ctx;
        try {
            ctx = dataScopeResolver.resolve(user.id());
        } catch (Exception e) {
            log.warn("[UserContext] 数据范围解析失败 userId={}: {}", user.id(), e.getMessage());
            sb.append("- 生效数据范围：解析失败（按最小范围仅本人对待）\n");
            return;
        }
        if (ctx.all()) {
            sb.append("- 生效数据范围：ALL——全部数据\n");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!ctx.deptIds().isEmpty()) {
            Map<Long, String> names = dataScopeResolver.getDeptNames(ctx.deptIds());
            StringJoiner deptDesc = new StringJoiner("、");
            ctx.deptIds().forEach(id -> deptDesc.add(names.getOrDefault(id, "部门#" + id)));
            parts.add("部门数据（" + deptDesc + "）");
        }
        if (ctx.self()) {
            parts.add("本人创建的数据");
        }
        if (parts.isEmpty()) {
            sb.append("- 生效数据范围：无（业务数据均不可见）\n");
        } else {
            sb.append("- 生效数据范围（多来源并集）：").append(String.join(" ∪ ", parts)).append("\n");
        }
    }

    /** "项目"类问法的指代与口径：可见范围严格按角色数据权限档位叠加，回答区分本人创建/非本人创建 */
    private void appendReferenceRules(StringBuilder sb, HuaxiUserInfo user) {
        sb.append("\n### 用户问\"项目\"时的指代与口径\n");
        sb.append("- **可见范围的构成（严格按档位叠加，无默认组合）**：")
                .append("按上方\"角色数据范围\"逐角色并集计算（六种档位：全部/指定部门+本部门/本部门/本部门及以下/本人/兼职部门）；")
                .append("**\"本人创建\"仅当角色含\"本人\"档时才生效**，不存在默认的\"本人+兼职部门\"之类的组合。\n");
        sb.append("- \"我能看到哪些项目 / 我能看多少项目 / 项目清单 / 全部项目\"（问可见范围）：直接查 doc_project，")
                .append("**不要手写任何范围过滤**（creator=、dept_id= 等）——系统按上面的数据范围自动注入，")
                .append("默认即按角色数据权限过滤，无需用户在问题里说明。\n");
        sb.append("- \"我的 / 我创建的项目\"（问个人关系）：可见范围内加 creator=当前用户 过滤（同样受数据权限约束）。\n");
        sb.append("- **清单/数量类回答必须区分归属**：查询 SELECT 带上 creator 列，先展示/统计\"本人创建\"")
                .append("（creator=当前用户），再展示/统计\"权限范围内其他\"（creator≠当前用户，来自部门/兼职部门等档位）。\n");
        sb.append("- 草稿项目（status=0）仅创建者本人可见，系统已自动注入 (status <> 0 OR creator=当前用户)，不要手写该条件。\n");
        sb.append("- 预警按责任人 responsible_user_id；通用数据按 creator。\n");
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
            sb.append("- **无权限＝查不到**：对不在数据范围内的对象，只回答\"未在您的数据范围内找到\"，")
                    .append("不透露其存在性与任何字段值；严禁用 OR 连接等方式构造可能放大可见范围的查询，")
                    .append("也不要在思考或回答里分析注入的条件与内部 ID。\n");
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

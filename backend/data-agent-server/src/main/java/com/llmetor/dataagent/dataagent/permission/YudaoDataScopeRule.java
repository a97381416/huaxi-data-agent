package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 华西 yudao 数据权限规则（M-H4 并集版）：按表特征分派注入策略，
 * 条件由 {@link DataScopeContext} 各落点 OR 组合（放行语义），
 * 与主后端 DeptDataPermissionRule 的 {@code (dept_id IN .. OR creator=.. OR id IN ..)} 同构。
 *
 * <ul>
 *   <li><b>doc_project</b>（项目主表）：dept_id IN (部门并集，含兼职部门) OR creator=本人（self 档）；
 *       并 <b>无条件追加草稿可见性 (status &lt;&gt; 0 OR creator=本人)</b>
 *       ——对齐主后端 DocProjectMapper.selectPage：草稿仅创建者可见，ALL 档同样生效</li>
 *   <li><b>project_id 台账表</b>（ledger/file_entry/stats/批注/五卡片/biztable 23 张等）：
 *       project_id IN (SELECT id FROM doc_project WHERE &lt;同上权限组合&gt; AND deleted=0)。
 *       子查询内<b>不加草稿过滤</b>——主后端台账可见性=项目可见性（含他人草稿项目的台账），
 *       注入草稿条件会使统计口径比管理页偏小</li>
 *   <li><b>doc_warning</b>：dept_id IN (...) OR responsible_user_id=本人（self 档；
 *       对齐主后端 user 列注册，不再附带 creator）</li>
 *   <li><b>doc_warning_event</b>：warning_id IN (SELECT id FROM doc_warning WHERE &lt;预警权限组合&gt;)</li>
 *   <li><b>doc_project_member 不纳管</b>：业务已废弃（7 档项目成员权限已下线，表也已移出
 *       schema 白名单），仅保留不注入行为</li>
 * </ul>
 *
 * <p>未纳管的表（system_users、system_dept、system_dict 两表等元数据表，以及
 * ru_task、hi_task 等流程表——流程数据当前全员可见，联调时按需收紧）不注入条件。
 * 条件文本经 {@link ConditionBuilder#parse} 构造（ref 为 AST 解析的受控别名、
 * userId 为 Long 内网受控值，无注入面）。权限并集为空 → 1 = 0 恒假（fail-closed，
 * 对齐主后端 WHERE null = null 返回空集）。
 */
@Component
public class YudaoDataScopeRule implements PermissionRule {

    static final String PROJECT_TABLE = "doc_project";

    static final Set<String> PROJECT_ID_TABLES = Set.of(
            // 填报事实
            "doc_ledger", "doc_file_entry", "doc_file_entry_history", "doc_sub_phase_stats",
            // 项目档案子表（doc_project_member 已废弃移出白名单，不在纳管范围）
            "doc_project_annotation", "doc_project_annotation_item", "doc_project_annotation_attachment",
            "doc_project_payment_progress",
            "doc_project_invest_completion", "doc_project_budget_estimate",
            "doc_project_fee_audit", "doc_project_advance", "doc_project_output_value",
            // 节点业务动态表格 23 张
            "doc_fund_plan_annual", "doc_fund_plan_annual_row",
            "doc_fund_plan_quarterly", "doc_fund_plan_quarterly_row",
            "doc_fund_plan_monthly", "doc_fund_plan_monthly_row",
            "doc_fund_payment", "doc_fund_payment_row",
            "doc_debt_statistics", "doc_debt_statistics_row",
            "doc_debt_detail", "doc_debt_detail_row",
            "doc_lifecycle_cost_round", "doc_lifecycle_cost_row",
            "doc_meeting_info", "doc_meeting_info_row",
            "doc_site_inspection", "doc_site_inspection_row",
            "doc_subsection_acceptance", "doc_subsection_acceptance_row",
            "doc_biz_output_value", "doc_biz_output_value_row"
    );

    static final Set<String> WARNING_TABLES = Set.of("doc_warning", "doc_warning_event");

    @Override
    public Set<String> managedTables() {
        Set<String> all = new java.util.HashSet<>(PROJECT_ID_TABLES);
        all.addAll(WARNING_TABLES);
        all.add(PROJECT_TABLE);
        // doc_project_member 不纳管：7 档项目成员权限已废弃，该表也已移出 schema 白名单
        return Set.copyOf(all);
    }

    @Override
    public Expression buildCondition(String ref, String tableName, DataScopeContext ctx) {
        if (ctx.all()) {
            // ALL：权限段短路；仅 doc_project 的草稿可见性规则对所有人生效（对齐主后端页面口径）
            return PROJECT_TABLE.equals(tableName)
                    ? ConditionBuilder.parse(draftSql(ref, ctx.userId()))
                    : null;
        }
        return switch (tableName) {
            case PROJECT_TABLE -> ConditionBuilder.parse(
                    "(" + projectPermSql(ref, ctx) + ") AND " + draftSql(ref, ctx.userId()));
            // 权限段自身含 OR（部门 ∪ 责任人），外层必须带括号——与挂载处 Parenthesis 同理，
            // 防止 AND 优先级把 OR 右分支缩成绕过通道
            case "doc_warning" -> ConditionBuilder.parse("(" + warningPermSql(ref, ctx) + ")");
            case "doc_warning_event" -> ConditionBuilder.parse(
                    ref + ".warning_id IN (SELECT id FROM doc_warning WHERE ("
                            + warningPermSql("doc_warning", ctx) + ") AND deleted = 0)");
            // project_id 台账表：项目可见性驱动，子查询不加草稿过滤（见类注释）
            default -> ConditionBuilder.parse(
                    ref + ".project_id IN (SELECT id FROM " + PROJECT_TABLE + " WHERE ("
                            + projectPermSql(PROJECT_TABLE, ctx) + ") AND deleted = 0)");
        };
    }

    // ── doc_project 权限组合：部门（含兼职） ∪ 本人创建 ──

    /** doc_project 上的权限 OR 组合（ref 为该表引用名；并集为空 → 1 = 0 恒假） */
    private String projectPermSql(String ref, DataScopeContext ctx) {
        List<String> segs = new ArrayList<>();
        if (!ctx.deptIds().isEmpty()) {
            segs.add(ref + ".dept_id IN (" + joinIds(ctx.deptIds()) + ")");
        }
        if (ctx.self()) {
            segs.add(ref + ".creator = '" + ctx.userId() + "'");
        }
        return segs.isEmpty() ? "1 = 0" : String.join(" OR ", segs);
    }

    /** 草稿可见性（业务规则，非数据权限）：草稿(status=0)仅创建者本人可见，对 ALL 档同样生效 */
    private String draftSql(String ref, Long userId) {
        return "(" + ref + ".status <> 0 OR " + ref + ".creator = '" + userId + "')";
    }

    /** doc_warning 权限组合：部门 ∪ 责任人（self 档，对齐主后端 user 列注册） */
    private String warningPermSql(String ref, DataScopeContext ctx) {
        List<String> segs = new ArrayList<>();
        if (!ctx.deptIds().isEmpty()) {
            segs.add(ref + ".dept_id IN (" + joinIds(ctx.deptIds()) + ")");
        }
        if (ctx.self()) {
            segs.add(ref + ".responsible_user_id = " + ctx.userId());
        }
        return segs.isEmpty() ? "1 = 0" : String.join(" OR ", segs);
    }

    /** 集合 → "1,2,3"（服务端解析的部门 ID，受控数值无注入面；空集 → -1 恒 false） */
    private static String joinIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "-1";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}

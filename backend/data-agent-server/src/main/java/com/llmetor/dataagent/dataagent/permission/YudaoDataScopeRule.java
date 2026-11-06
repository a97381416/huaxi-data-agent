package com.llmetor.dataagent.dataagent.permission;

import net.sf.jsqlparser.expression.Expression;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 华西 yudao 数据权限规则（M-H2）：按表特征分派三组注入策略。
 *
 * <ul>
 *   <li><b>doc_project</b>（项目主表）：SELF = id IN (成员表) OR creator=本人；
 *       DEPT 系 = dept_id IN (...)</li>
 *   <li><b>project_id 台账表</b>（ledger/file_entry/stats/批注/五卡片/biztable 23 张等）：
 *       SELF = project_id IN (SELECT project_id FROM doc_project_member WHERE user_id=...) OR creator=本人；
 *       DEPT 系 = project_id IN (SELECT id FROM doc_project WHERE dept_id IN (...))</li>
 *   <li><b>doc_warning</b>：SELF = responsible_user_id=本人 OR creator=本人；DEPT 系 = dept_id IN (...)</li>
 * </ul>
 *
 * <p>未纳管的表（system_users、system_dept、system_dict 两表等元数据表，以及
 * ru_task、hi_task 等流程表——流程数据当前全员可见，联调时按需收紧）不注入条件。
 * 条件文本经 {@link ConditionBuilder#parse} 构造（ref 为 AST 解析的受控别名、
 * userId 为 Long 内网受控值，无注入面）。
 */
@Component
public class YudaoDataScopeRule implements PermissionRule {

    static final String PROJECT_TABLE = "doc_project";

    static final Set<String> PROJECT_ID_TABLES = Set.of(
            // 填报事实
            "doc_ledger", "doc_file_entry", "doc_file_entry_history", "doc_sub_phase_stats",
            // 项目档案子表（doc_project_member 单独分派：SELF 简单等值——
            // 它作为权限子查询载体，若走 project_id 子查询会自引用无限递归栈溢出）
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

    /** 成员表：权限子查询的载体，SELF=简单等值防自引用递归 */
    static final String MEMBER_TABLE = "doc_project_member";

    @Override
    public Set<String> managedTables() {
        Set<String> all = new java.util.HashSet<>(PROJECT_ID_TABLES);
        all.addAll(WARNING_TABLES);
        all.add(PROJECT_TABLE);
        all.add(MEMBER_TABLE);
        return Set.copyOf(all);
    }

    // ── SELF ──

    @Override
    public Expression buildSelfCondition(String ref, String tableName, Long userId) {
        String memberSub = "SELECT project_id FROM doc_project_member WHERE user_id = " + userId + " AND deleted = 0";
        return switch (tableName) {
            case PROJECT_TABLE -> ConditionBuilder.parse(
                    "(" + ref + ".id IN (" + memberSub + ") OR " + ref + ".creator = '" + userId + "')");
            case MEMBER_TABLE -> ConditionBuilder.parse(
                    ref + ".user_id = " + userId);
            case "doc_warning" -> ConditionBuilder.parse(
                    "(" + ref + ".responsible_user_id = " + userId + " OR " + ref + ".creator = '" + userId + "')");
            case "doc_warning_event" -> ConditionBuilder.parse(
                    "(" + ref + ".warning_id IN (SELECT id FROM doc_warning WHERE responsible_user_id = " + userId
                            + " AND deleted = 0) OR " + ref + ".creator = '" + userId + "')");
            default -> ConditionBuilder.parse(
                    "(" + ref + ".project_id IN (" + memberSub + ") OR " + ref + ".creator = '" + userId + "')");
        };
    }

    // ── DEPT / DEPT_AND_SUB / CUSTOM ──

    @Override
    public Expression buildDeptCondition(String ref, String tableName, Set<Long> deptIdAndSubIds) {
        String deptIn = joinIds(deptIdAndSubIds);
        return switch (tableName) {
            case PROJECT_TABLE -> ConditionBuilder.parse(
                    ref + ".dept_id IN (" + deptIn + ")");
            case "doc_warning" -> ConditionBuilder.parse(
                    ref + ".dept_id IN (" + deptIn + ")");
            case MEMBER_TABLE -> ConditionBuilder.parse(
                    ref + ".user_id IN (SELECT id FROM system_users WHERE dept_id IN (" + deptIn + ") AND deleted = 0)");
            case "doc_warning_event" -> ConditionBuilder.parse(
                    ref + ".warning_id IN (SELECT id FROM doc_warning WHERE dept_id IN (" + deptIn + ") AND deleted = 0)");
            default -> ConditionBuilder.parse(
                    ref + ".project_id IN (SELECT id FROM doc_project WHERE dept_id IN (" + deptIn + ") AND deleted = 0)");
        };
    }

    /** 集合 → "1,2,3"（部门树展开结果，受控数值无注入面） */
    private static String joinIds(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.length() > 0 ? sb.toString() : "-1"; // 空集 → IN (-1) 恒 false（fail-closed）
    }
}

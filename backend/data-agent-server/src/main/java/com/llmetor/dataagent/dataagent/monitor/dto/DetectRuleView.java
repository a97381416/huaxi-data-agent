package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.Map;

/**
 * 规则注册表一行（GET /monitor/detect/rules 弹层数据源 / PUT /monitor/detect/rules/{ruleId} 回显）。
 *
 * @param params        当前生效阈值（配置表行覆盖 + 代码默认补齐）
 * @param defaultParams 代码默认阈值（「恢复默认」用）
 * @param defaultEnabled 代码默认开关（TOOL-005=false，其余 true）
 * @param remark        规则备注（配置表人工维护，仅说明用，不参与检测判定；可为 null）
 */
public record DetectRuleView(
        String ruleId,
        String title,
        String severity,
        String description,
        String remark,
        boolean enabled,
        Map<String, Object> params,
        Map<String, Object> defaultParams,
        boolean defaultEnabled,
        String updatedBy,
        String updatedAt) {
}

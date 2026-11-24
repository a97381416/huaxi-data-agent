package com.llmetor.dataagent.dataagent.monitor.dto;

/**
 * 单问检测结果（POST /monitor/sessions/{sessionId}/detect 同步返回 + 异步检测内部产物）。
 *
 * @param detectStatus    done / failed（running 行不可检测）
 * @param firstRisk       首个最高级风险一句话（同 first_error 交互）
 * @param detectorVersion 引擎版本（DetectRuleRegistry.VERSION）
 */
public record DetectSummary(
        String sessionId,
        String detectStatus,
        int riskTotal,
        int riskHigh,
        String firstRisk,
        String detectorVersion) {
}

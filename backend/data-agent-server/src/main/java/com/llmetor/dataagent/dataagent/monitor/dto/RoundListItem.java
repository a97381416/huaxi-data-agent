package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 轮次瘦字段行（会话详情页时间轴用，设计 §6.7：明细点开才调 /monitor/rounds/{traceId}）。
 *
 * @param roundType  tool/text/error（瘦表分析列；历史行为 NULL）
 * @param toolNames  本轮调用的工具名（工具轮才有）
 * @param inputChars 明细体积（前端 >100KB 先摘要 + 「加载完整明细」确认）
 */
public record RoundListItem(
        String traceId,
        String sessionId,
        int round,
        String roundType,
        List<String> toolNames,
        long promptTokens,
        long completionTokens,
        long durationMs,
        Long thinkDurationMs,
        boolean success,
        String errorMessage,
        Integer inputChars,
        String createdAt) {
}

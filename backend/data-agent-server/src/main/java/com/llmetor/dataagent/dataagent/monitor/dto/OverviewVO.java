package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 监控总览（/monitor/overview，设计 §6.1 Dashboard 数据源）。
 *
 * @param range    10m/30m/1h/24h/7d
 * @param kpi      KPI 卡：convs/rounds/failRounds/successRate(0-1)/errorConvs/p95Ms/promptTokens/completionTokens
 * @param trend    趋势点（5 分钟桶）：convs/rounds/failRounds
 * @param perf     失败率 & P95 趋势点：failRate(0-1)/p95Ms（桶内无轮次时字段为 null，前端断线）
 * @param topFailed 失败会话 TOP 10（窗口内失败轮数倒序）
 * @param topDuration/topRounds/topTokens 耗时/多轮/多 token 会话 TOP 10（窗口内对应指标倒序）
 * @param running  进行中会话（stuck=超阈值疑似卡死，前端给「置为 interrupted」操作）
 */
public record OverviewVO(
        String range,
        Kpi kpi,
        List<TrendPoint> trend,
        List<PerfPoint> perf,
        List<TopFailedItem> topFailed,
        List<TopSessionItem> topDuration,
        List<TopSessionItem> topRounds,
        List<TopSessionItem> topTokens,
        List<RunningSession> running) {

    public record Kpi(long convs, long rounds, long failRounds, Double successRate,
                      long errorConvs, Double p95Ms, long promptTokens, long completionTokens,
                      long traceWriteFailures) {
    }

    public record TrendPoint(String minute, long convs, long rounds, long failRounds) {
    }

    public record PerfPoint(String minute, Double failRate, Double p95Ms) {
    }

    /**
     * 失败提问 TOP 条目（提问维度，2026-11-19 评审反馈）。
     *
     * @param sessionId 提问 ID（深链直达该提问）
     * @param lastRound 首个失败轮号（提问内）
     */
    public record TopFailedItem(String conversationId, String sessionId, String userId, String question,
                                String status, long failRounds, Integer lastRound, String lastError,
                                Long totalMs, String lastAt) {
    }

    /** 会话 TOP 条目（耗时/轮数/token 三联看板共用，value 语义随所属看板：ms/轮数/token 数） */
    public record TopSessionItem(String conversationId, String question, long value) {
    }

    public record RunningSession(String conversationId, String userId, String question,
                                 String startedAt, long runningMs, long rounds, boolean stuck) {
    }
}

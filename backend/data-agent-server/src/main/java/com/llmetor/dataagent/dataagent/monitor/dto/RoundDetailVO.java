package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 单轮明细（/monitor/rounds/{traceId}，payload 懒加载，设计 §5.2）。
 *
 * <p>raw 三字段是 payload 原文（迁移期 payload 缺行回退瘦表旧列）；
 * rendered 是 TraceQueryService 契约渲染（tools 定义按 includeTools 开关省略/附带），
 * 供直接展示文本视图复用；前端 JSON 树视图用 raw。
 */
public record RoundDetailVO(
        String traceId,
        String sessionId,
        String conversationId,
        int round,
        String roundType,
        List<String> toolNames,
        boolean success,
        String errorMessage,
        long durationMs,
        Long thinkDurationMs,
        long promptTokens,
        long completionTokens,
        Integer inputChars,
        String createdAt,
        String inputData,
        String outputData,
        String think,
        String rendered) {
}

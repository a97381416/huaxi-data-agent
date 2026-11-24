package com.llmetor.dataagent.dataagent.monitor.dto;

/**
 * 提问列表行（/monitor/sessions，一行 = 一次提问）。
 *
 * <p>监控中心主视图切「提问」维度（2026-11-19 评审反馈）：数据源 = agentx_monitor_session
 * 单表（一问一行），筛选全部单表 WHERE 下推；每行自带该提问的全链路瘦字段
 * （问题原文 / 最终回答预览 / 状态 / 轮次 / 失败轮 / 耗时），无需进详情即可定位。
 *
 * @param conversationTitle 会话摘要（monitor_conversation.title，首问截断；列表直出 + 模糊搜索）
 * @param status        running/completed/error/interrupted（提问状态）
 * @param answerPreview 最终回答预览（截断摘要，全文看详情/终答轮 OUTPUT）
 * @param answerTraceId 终答所在轮（字符串传输防 JS 精度丢失；前端深链直达）
 * @param firstError    首个失败轮错误（失败行免点开直出）
 * @param durationMs    提问墙钟耗时（closeSession 回填；running 行为 null，前端按 now−created_at 实时算）
 * @param riskTotal     风险总数（Step3 检测回填；detect_status<>'done' 时不可信，前端显示「未检测」）
 * @param riskHigh      高危风险数（❗红标直出）
 * @param firstRisk     首个最高级风险一句话（hover 展示，同 first_error 交互）
 * @param detectStatus  检测状态 pending/done/failed（空=未检测，历史行）
 * @param detectorVersion 检测引擎版本（v1/v2；V2 批量重诊断按版本圈定与展示）
 */
public record SessionPageItem(
        String conversationId,
        String conversationTitle,
        String sessionId,
        int seq,
        String userId,
        String question,
        String status,
        String answerPreview,
        Integer answerChars,
        String answerTraceId,
        Long rounds,
        Long failRounds,
        String firstError,
        long promptTokens,
        long completionTokens,
        Long durationMs,
        String createdAt,
        String completedAt,
        Integer riskTotal,
        Integer riskHigh,
        String firstRisk,
        String detectStatus,
        String detectorVersion) {
}

package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 会话详情（/monitor/conversations/{convId}，设计 §6.4 三栏布局左栏 + 中栏数据源）。
 *
 * <p>监控中心自有表方案（§5）：返回该窗口<b>全部提问</b>（question/answer/seq/status），
 * 轮次按 session_id+round 归属，前端时间轴按提问分组渲染（Q1·R1…Rn / Q2·R1…Rm）。
 *
 * @param status  窗口聚合态（口径同 SessionPageItem 的提问状态聚合）
 * @param totalMs 窗口墙钟耗时
 * @param rounds  轮次瘦字段列表（只含瘦字段，明细懒加载；sessionId 归属提问分组）
 * @param sessions 窗口内全部提问（一次提问一行，seq=窗口内序号，answer=终态回填最终回答）
 */
public record ConversationDetailVO(
        String conversationId,
        String userId,
        String question,
        String status,
        int sessionCount,
        long totalMs,
        long promptTokens,
        long completionTokens,
        long failRounds,
        String startedAt,
        String lastActiveAt,
        List<RoundListItem> rounds,
        List<SessionItem> sessions) {

    /**
     * 窗口内一次提问（agentx_monitor_session 一行）。
     *
     * @param answerTraceId 终答所在轮 trace id（字符串传输，雪花 ID 超 JS 安全整数精度），点击直达其 OUTPUT
     * @param userId        提问人（提问详情基本信息条直出）
     * @param promptTokens  该提问输入 token（closeSession 回填）
     * @param completionTokens 该提问输出 token（closeSession 回填）
     * @param riskTotal     风险总数（Step3 检测回填；检测区域状态头直出）
     * @param riskHigh      高危风险数
     * @param detectStatus  检测状态 pending/done/failed（空=未检测，历史行）
     */
    public record SessionItem(
            String sessionId,
            int seq,
            String userId,
            String question,
            String answer,
            Integer answerChars,
            String answerTraceId,
            String status,
            Integer rounds,
            Integer failRounds,
            Long durationMs,
            Long promptTokens,
            Long completionTokens,
            String createdAt,
            String completedAt,
            Integer riskTotal,
            Integer riskHigh,
            String detectStatus) {
    }
}

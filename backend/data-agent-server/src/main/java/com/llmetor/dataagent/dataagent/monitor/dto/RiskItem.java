package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;
import java.util.Map;

/**
 * 风险项（GET /monitor/sessions/{sessionId}/risks，详情页自动检测卡数据源；
 * 按 severity desc, round asc 排序返回）。
 *
 * @param traceId   主定位轮 trace 行 id（字符串传输防 JS 精度丢失；深链 ?sessionId=&round= 用 round）
 * @param roundsHit 跨轮规则涉及轮清单（"5,6,7" 拆开的轮号列表，前端渲染 [R5][R6][R7] 胶囊）
 * @param evidence  结构化证据（args哈希/观察摘录/比对数字；jsonb 解析回 Map）
 * @param sessionId 风险归属会话 ID（M-TT：等于根 = 主链路；等于某子会话 = 子Agent命中，前端打「子Agent」徽标并按子命名空间定位）
 * @param agentCode 风险归属 agent 编码（M-TT：join agentx_monitor_session；router/空=主链路）
 */
public record RiskItem(
        String ruleId,
        String severity,
        Integer round,
        String traceId,
        List<Integer> roundsHit,
        String title,
        String detail,
        Map<String, Object> evidence,
        String createdAt,
        String sessionId,
        String agentCode) {
}

package com.llmetor.dataagent.dataagent.monitor.dto;

import java.util.List;

/**
 * 快捷搜索建议项（/monitor/search，命令面板数据源，设计 §6.3）。
 *
 * <p>提问维度切换（2026-11-19 评审反馈）：question 模糊命中直接返回 type=session
 * 提问条目（问题原文 + 回答摘要，点击直达详情页定位该提问），不再按窗口聚合。
 *
 * @param type session=提问（精确反查/模糊命中，点击直达该提问）| conversation=会话窗口（精确命中）| user=用户（跳列表页并预填 userId）
 */
public record SearchItem(
        String type,
        String conversationId,
        String sessionId,
        String userId,
        String question,
        String answerPreview,
        String status,
        Long rounds,
        Long failRounds,
        String startedAt,
        String lastActiveAt,
        Long conv7d,
        Long failConv7d) {

    /** 提问命中项（问题原文 + 回答摘要，sessionId 非空深链直达该提问） */
    public static SearchItem sessionHit(String conversationId, String sessionId, String question,
                                        String answerPreview, String status) {
        return new SearchItem("session", conversationId, sessionId, null,
                question, answerPreview, status, null, null, null, null, null, null);
    }

    /** 会话窗口命中项（question=窗口标题，可空） */
    public static SearchItem conversation(String conversationId, String question, String status,
                                          long rounds, long failRounds, String startedAt, String lastActiveAt) {
        return new SearchItem("conversation", conversationId, null, null, question, null, status,
                rounds, failRounds, startedAt, lastActiveAt, null, null);
    }

    /** 用户命中项（近 7 天提问数与失败提问数） */
    public static SearchItem user(String userId, long conv7d, long failConv7d) {
        return new SearchItem("user", null, null, userId, null, null, null,
                null, null, null, null, conv7d, failConv7d);
    }
}

package com.llmetor.dataagent.dataagent.session;

import java.util.List;

/**
 * 会话列表条目 VO（对齐原版 ConversationVO 契约）。
 *
 * @param conversationId 会话窗口 ID
 * @param title          首轮问题截 40 字
 * @param messageCount   问答轮次数（agentx_conversation 行数）
 * @param createdAt      首轮时间
 * @param lastActiveAt   最近一轮时间
 * @param streaming      是否有进行中的流（LiveStreamRegistry 批量探测）
 */
public record ConversationVO(String conversationId, String title, int messageCount,
                             String createdAt, String lastActiveAt, boolean streaming) {
}

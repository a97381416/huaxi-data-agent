package com.llmetor.dataagent.dataagent.session;

import java.util.List;

/**
 * 会话分页 VO（对齐原版 ConversationPage 契约）。
 */
public record ConversationPage(List<ConversationVO> conversations, int total,
                               int page, int size, boolean hasMore) {

    public static ConversationPage empty(int page, int size) {
        return new ConversationPage(List.of(), 0, page, size, false);
    }
}

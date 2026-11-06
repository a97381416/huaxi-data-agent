package com.llmetor.dataagent.dataagent.session;

import java.util.List;

/**
 * 会话详情 VO（对齐原版 ConversationDetailVO 契约）。
 */
public record ConversationDetailVO(String conversationId, List<SessionMessageVO> messages) {
}

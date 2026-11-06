package com.llmetor.miniagentx.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * offload 内容存取 SPI（docs/02 §4.3 压缩不丢信息闭环的存储侧）。
 *
 * <p>被压缩策略移出上下文的原文段，按 uuid 存档；LLM 看到
 * {@code uuid=xxx} 标记后可经 context_reload 工具按 uuid 取回原文。
 *
 * <p>业务侧实现：AgentxSessionStore 落 agentx_session 表的 offload_context
 * 状态键（无独立表）；内核另备内存实现供无 DB 场景与单测使用。
 */
public interface OffloadStore {

    /** 保存一段被 offload 的原文（同 uuid 重复保存覆盖） */
    void save(String conversationId, String uuid, List<Message> messages);

    /** 按 uuid 取回原文；不存在返回 null */
    List<Message> load(String conversationId, String uuid);
}

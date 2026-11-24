package com.llmetor.miniagentx.trace;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * trace 采集 SPI（监控中心自有表方案 §4.1）：AgentLoopExecutor 唯一的审计出口，
 * 内核只依赖本接口，具体落库由组合装配决定（旧 TraceManager / 监控 Collector / 组合）。
 *
 * <p>三个生命周期挂点与一次提问对齐：
 * <ul>
 *   <li>{@link #openSession}：提问开始（AgentLoopExecutor.initMessages，initConversation 同点位）</li>
 *   <li>{@link #trace}：每轮 LLM 调用收尾（原 TraceManager.trace 签名，行为不变）</li>
 *   <li>{@link #closeSession}：终态（persistTerminal 之后，status = completed/error/interrupted）</li>
 * </ul>
 *
 * <p>实现约定：所有方法自吞异常尽力而为（审计数据不阻断主流程）；
 * openSession/closeSession 提供 default 空实现，旧实现零改动即可接入。
 */
public interface TraceCollector {

    /**
     * 提问开始：一次用户提问一行。恢复执行（resume）不回调——session 行在原请求已建。
     *
     * @param sessionId      内核本次提问的 sessionId（与 agentx_conversation 1:1）
     * @param conversationId 对话窗口 ID
     * @param userId         用户 ID（可空字符串）
     * @param question       用户原始提问
     */
    default void openSession(String sessionId, String conversationId, String userId, String question) {
    }

    /**
     * 记一轮（每轮 LLM 调用一行，同 TraceManager.trace 契约）。
     *
     * @param thinkDurationMs 本轮"思考"单独耗时；无思考为 0
     */
    void trace(String sessionId, String conversationId, int round,
               List<Message> inputMessages, String outputData, String think,
               long promptTokens, long completionTokens, long durationMs,
               long thinkDurationMs, boolean success, String errorMessage);

    /**
     * 提问终态（persistTerminal 之后回调；每请求恰好一次，含 doFinally 兜底）。
     *
     * @param status      completed / error / interrupted
     * @param finalAnswer 最终回答（口径 = AnswerExtractor.extract(originalChain)；
     *                    仅 completed 非空，error/interrupted 传 null）
     */
    default void closeSession(String sessionId, String conversationId, String status, String finalAnswer) {
    }
}

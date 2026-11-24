package com.llmetor.miniagentx.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 组合采集器（监控中心自有表方案 §4.1）：按序扇出到多个 delegate，
 * 单个 delegate 抛异常只 warn 不影响其它 delegate 与主流程。
 *
 * <p>业务装配处（DodoAgent）用
 * {@code new CompositeTraceCollector(List.of(旧 TraceManager, 新 MonitorTraceCollector))}
 * 实现旧轨/新轨双写。
 */
public class CompositeTraceCollector implements TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(CompositeTraceCollector.class);

    private final List<TraceCollector> delegates;

    public CompositeTraceCollector(List<TraceCollector> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void openSession(String sessionId, String conversationId, String userId, String question) {
        openSession(sessionId, conversationId, userId, question, "router", null, null);
    }

    @Override
    public void openSession(String sessionId, String conversationId, String userId, String question,
                            String agentCode, String parentSessionId, String parentToolCallId) {
        for (TraceCollector d : delegates) {
            try {
                d.openSession(sessionId, conversationId, userId, question, agentCode, parentSessionId, parentToolCallId);
            } catch (Exception e) {
                log.warn("[trace-collector] openSession 失败（{}，不影响主流程）: convId={}",
                        d.getClass().getSimpleName(), conversationId, e);
            }
        }
    }

    @Override
    public void trace(String sessionId, String conversationId, int round,
                      List<Message> inputMessages, String outputData, String think,
                      long promptTokens, long completionTokens, long durationMs,
                      long thinkDurationMs, boolean success, String errorMessage) {
        trace(sessionId, conversationId, round, inputMessages, outputData, think,
                promptTokens, completionTokens, durationMs, thinkDurationMs, success, errorMessage,
                "router", null);
    }

    @Override
    public void trace(String sessionId, String conversationId, int round,
                      List<Message> inputMessages, String outputData, String think,
                      long promptTokens, long completionTokens, long durationMs,
                      long thinkDurationMs, boolean success, String errorMessage,
                      String agentCode, String parentSessionId) {
        for (TraceCollector d : delegates) {
            try {
                d.trace(sessionId, conversationId, round, inputMessages, outputData, think,
                        promptTokens, completionTokens, durationMs, thinkDurationMs, success, errorMessage,
                        agentCode, parentSessionId);
            } catch (Exception e) {
                log.warn("[trace-collector] trace 失败（{}，不影响主流程）: convId={}, round={}",
                        d.getClass().getSimpleName(), conversationId, round, e);
            }
        }
    }

    @Override
    public void closeSession(String sessionId, String conversationId, String status, String finalAnswer) {
        for (TraceCollector d : delegates) {
            try {
                d.closeSession(sessionId, conversationId, status, finalAnswer);
            } catch (Exception e) {
                log.warn("[trace-collector] closeSession 失败（{}，不影响主流程）: convId={}, sessionId={}, status={}",
                        d.getClass().getSimpleName(), conversationId, sessionId, status, e);
            }
        }
    }
}

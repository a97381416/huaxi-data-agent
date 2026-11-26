package com.llmetor.miniagentx.interrupt;

/**
 * 二期 HITL 挂起信号：工具线程在线等待人工审批超时（默认 60s）或服务端优雅停机时抛出，
 * 通知 {@code ToolCallExecutor} / {@code AgentLoopExecutor} 立即截断同批次后续工具、
 * 落盘 {@code PauseState(HITL_TOOL_REQUEST)} 并以 {@code PAUSED} 优雅收尾释放线程。
 */
public class HitlParkException extends RuntimeException {

    private final String decisionId;

    public HitlParkException(String decisionId, String message) {
        super(message);
        this.decisionId = decisionId;
    }

    public String getDecisionId() {
        return decisionId;
    }
}

package com.llmetor.miniagentx.model;

import lombok.Getter;
import lombok.ToString;

/**
 * Agent 调用结果三态。
 *
 * <ul>
 *   <li>Completed：answer 为最终答案文本；outputType 指定时已经过 JsonRepairUtil 修复</li>
 *   <li>Paused：携带中断快照</li>
 *   <li>Failed：LLM 流错误且重试耗尽</li>
 * </ul>
 */
@Getter
@ToString
public class AgentResult {

    public enum Status { COMPLETED, PAUSED, FAILED }

    private final Status status;
    private final String answer;
    private final String think;
    private final PauseState pauseState;
    private final String error;
    private final String code;

    private AgentResult(Status status, String answer, String think, PauseState pauseState,
                        String error, String code) {
        this.status = status;
        this.answer = answer;
        this.think = think;
        this.pauseState = pauseState;
        this.error = error;
        this.code = code;
    }

    public static AgentResult completed(String answer, String think) {
        return new AgentResult(Status.COMPLETED, answer, think, null, null, null);
    }

    public static AgentResult paused(PauseState state) {
        return new AgentResult(Status.PAUSED, null, null, state, null, null);
    }

    public static AgentResult failed(String code, String error) {
        return new AgentResult(Status.FAILED, null, null, null, error, code);
    }
}

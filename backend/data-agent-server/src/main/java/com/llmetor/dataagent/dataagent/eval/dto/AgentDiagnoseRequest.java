package com.llmetor.dataagent.dataagent.eval.dto;

/**
 * 错题诊断请求（M8；runner 从状态 JSONL 挑"判不通过且总分最低"的一遍送入）。
 *
 * @param conversationId 被测会话窗口 ID（诊断 Agent 沿它查 trace）
 * @param question       用户问题
 * @param goldAnswer     标准答案（预执行固化）
 * @param agentAnswer    Agent 实际回答（裁判判不通过）
 * @param judgeReason    裁判判语
 */
public record AgentDiagnoseRequest(String conversationId, String question, String goldAnswer,
                                   String agentAnswer, String judgeReason) {
}

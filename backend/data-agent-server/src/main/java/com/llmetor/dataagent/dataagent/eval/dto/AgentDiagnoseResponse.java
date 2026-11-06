package com.llmetor.dataagent.dataagent.eval.dto;

/**
 * 错题诊断响应（契约对齐原版）。
 *
 * @param success        诊断调用是否成功
 * @param conversationId 被测会话窗口 ID
 * @param rootSessionId  根源错误所在 session 编号
 * @param rootRound      根源错误轮次（第一次出错的轮，不是最后出错轮）
 * @param attribution    归因四选一：模型能力/提示词/工具/框架
 * @param evidence       trace 证据（引用具体 session、round、item_index 与消息原文片段）
 * @param analysis       问题分析
 * @param suggestion     修改建议（可执行）
 * @param error          失败原因（success=false 时）
 * @param durationMs     诊断耗时
 */
public record AgentDiagnoseResponse(boolean success, String conversationId, String rootSessionId,
                                    Integer rootRound, String attribution, String evidence,
                                    String analysis, String suggestion, String error,
                                    long durationMs) {
}

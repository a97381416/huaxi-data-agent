package com.llmetor.dataagent.dataagent.eval.dto;

/**
 * 裁判判分请求（M8，本地 runner 专用、免鉴权）。
 *
 * <p>多轮题由 runner 把完整对话记录（"第n轮\n用户：…\nAgent：…"）拼进 agentAnswer 一次送判，
 * 契约保持单轮三段式不变（对齐原版）。
 *
 * @param question    用户问题（多轮题按轮次渲染）
 * @param goldAnswer  标准答案（出题时预执行固化）
 * @param agentAnswer Agent 报告原文（超 8000 字截断送判）
 */
public record AgentEvalJudgeRequest(String question, String goldAnswer, String agentAnswer) {
}

package com.llmetor.dataagent.dataagent.eval.dto;

/**
 * 裁判判分响应（契约对齐原版）。
 *
 * <p>pass 与 totalScore 由程序计算（pass = correctness ≥ 3，权重 0.4/0.2/0.2/0.2），
 * 不经裁判自由发挥。
 *
 * @param success      本次判分调用是否成功
 * @param pass         是否通过（correctness ≥ 3）
 * @param correctness  正确性 0-5（数值对不对、有没有编造）
 * @param completeness 完整性 0-5（要点覆盖）
 * @param relevance    相关性 0-5（跑题/乱补充）
 * @param logic        逻辑性 0-5（组织与推理）
 * @param totalScore   加权总分（round2）
 * @param reason       裁判判语（错题排查第一线索）
 * @param error        失败原因（success=false 时）
 * @param durationMs   判分耗时
 */
public record AgentEvalJudgeResponse(boolean success, boolean pass, int correctness,
                                     int completeness, int relevance, int logic,
                                     double totalScore, String reason, String error,
                                     long durationMs) {
}

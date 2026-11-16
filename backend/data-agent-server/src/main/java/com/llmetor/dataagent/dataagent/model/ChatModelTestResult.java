package com.llmetor.dataagent.dataagent.model;

/**
 * 对话模型连通测试结果（POST /api/models/test；保存前后均可调用）。
 *
 * <p>测试 = 按给定配置独立构建 ChatModel → 发一条极小对话补全 → 关闭，
 * 成功也不缓存复用（与保存是两次独立调用，代价可接受、语义最简单）。
 *
 * @param ok        是否连通（HTTP 调用不抛异常即成功；模型可能只回推理内容，不校验文本）
 * @param reply     模型回复预览（截断到 200 字符；推理型模型可能为空）
 * @param elapsedMs 耗时
 * @param error     失败原因（成功为 null）
 */
public record ChatModelTestResult(boolean ok, String reply, long elapsedMs, String error) {
}

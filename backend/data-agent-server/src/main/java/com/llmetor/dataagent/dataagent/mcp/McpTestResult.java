package com.llmetor.dataagent.dataagent.mcp;

import java.util.List;

/**
 * MCP 连接测试结果（POST /api/mcp/servers/test；保存前后均可调用）。
 *
 * <p>测试 = 独立建客户端 → initialize 握手 → listTools 拉清单 → 关闭，
 * 成功也不缓存复用（与保存是两次独立连接，代价可接受、语义最简单）。
 *
 * @param ok        是否连通（握手 + 拉工具都成功）
 * @param toolCount 发现的工具个数
 * @param toolNames 工具名清单（最多全部列出，前端测试反馈区直接展示）
 * @param elapsedMs 耗时
 * @param error     失败原因（成功为 null）
 */
public record McpTestResult(boolean ok, int toolCount, List<String> toolNames, long elapsedMs, String error) {
}

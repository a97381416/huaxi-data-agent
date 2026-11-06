package com.llmetor.dataagent.dataagent.mcp;

/**
 * MCP 服务已暴露的单个工具（/api/mcp/servers/{id}/tools 工具预览抽屉行）。
 *
 * @param name        工具名（即 Agent 工具清单里的名字）
 * @param description 工具描述（MCP server 侧声明）
 */
public record McpToolInfo(String name, String description) {
}

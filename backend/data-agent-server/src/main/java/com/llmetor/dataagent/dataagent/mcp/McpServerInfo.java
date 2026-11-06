package com.llmetor.dataagent.dataagent.mcp;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * MCP 服务信息 VO（/api/mcp/servers 返回体）。
 *
 * <p>凭据脱敏契约：authToken → maskToken 形态（sk-***b4）；env 值对疑似密钥键
 * （含 KEY/TOKEN/SECRET/PASSWORD）脱敏。前端回传含 "***" 的值视为未修改
 * （保存时用库中原文替换），见 McpServerManager#mergeSecrets。
 *
 * @param id          主键
 * @param name        服务标识（唯一，创建后不可改）
 * @param transport   STREAMABLE / SSE / STDIO
 * @param url         HTTP 系端点（STDIO 为 null）
 * @param sseEndpoint SSE 端点路径（默认 /sse）
 * @param command     STDIO 启动命令
 * @param args        STDIO 参数（JSON 数组字符串原样回显）
 * @param env         STDIO 环境变量（值已按密钥规则脱敏）
 * @param authType    NONE / BEARER
 * @param tokenMasked Bearer 凭据脱敏形态（未配置为空串）
 * @param timeoutMs   工具调用/握手超时毫秒
 * @param injectMode  ALWAYS / ONLINE_TOGGLE
 * @param enabled     是否启用（启用才进注册表）
 * @param source      yml / manual
 * @param status      OK / ERROR / UNKNOWN
 * @param lastError   最近一次连接失败原因（截断）
 * @param toolCount   最近一次成功拉取的工具个数
 * @param lastCheckTime 最近连通性检查时间
 */
public record McpServerInfo(
        Long id,
        String name,
        String transport,
        String url,
        String sseEndpoint,
        String command,
        String args,
        Map<String, String> env,
        String authType,
        String tokenMasked,
        Integer timeoutMs,
        String injectMode,
        boolean enabled,
        String source,
        String status,
        String lastError,
        Integer toolCount,
        LocalDateTime lastCheckTime,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

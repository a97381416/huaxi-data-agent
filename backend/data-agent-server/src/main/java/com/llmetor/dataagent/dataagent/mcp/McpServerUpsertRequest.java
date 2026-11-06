package com.llmetor.dataagent.dataagent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MCP 服务新建/编辑请求体。
 *
 * <p>凭据"不修改"语义（编辑弹窗凭据框回显脱敏值，原样回传即视为未修改）：
 * <ul>
 *   <li>{@code authToken}：null 或含 "***" → 保持库中原值；空串 → 清除；其他 → 覆盖</li>
 *   <li>{@code env}：null → 保持；JSON 对象内含 "***" 的值逐键用库中原值替换</li>
 *   <li>非凭据字段：null → 保持原值（前端始终传当前表单值，等价全量更新）</li>
 * </ul>
 *
 * @param name        服务标识（编辑时忽略，name 不可改）
 * @param transport   STREAMABLE / SSE / STDIO
 * @param url         HTTP 系端点
 * @param sseEndpoint SSE 端点路径（默认 /sse）
 * @param command     STDIO 启动命令
 * @param args        STDIO 参数 JSON 数组字符串
 * @param env         STDIO 环境变量 JSON 对象字符串
 * @param authType    NONE / BEARER（STDIO 强制 NONE）
 * @param authToken   Bearer 凭据（null 或含 *** = 不修改）
 * @param timeoutMs   超时毫秒（1000~300000）
 * @param injectMode  ALWAYS / ONLINE_TOGGLE
 * @param enabled     仅创建时生效（true 默认；编辑走 /toggle 接口）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerUpsertRequest(
        String name,
        String transport,
        String url,
        String sseEndpoint,
        String command,
        String args,
        String env,
        String authType,
        String authToken,
        Integer timeoutMs,
        String injectMode,
        Boolean enabled) {

    /** 凭据是否为"保持不变"语义（null 或含掩码标记 ***） */
    public boolean tokenUnchanged() {
        return authToken == null || authToken.contains("***");
    }
}

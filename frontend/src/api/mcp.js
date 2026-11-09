/**
 * /api/mcp/servers MCP 服务管理接口（admin 专用，对应后端 McpController）。
 * request 封装已处理 Authorization Bearer 头 / R<T> 解包 / 401 跳转，此处只声明端点。
 *
 * 凭据契约：后端脱敏回传（token → sk-***b4 形态；env 值对疑似密钥键脱敏）；
 * 编辑时原样回传含 "***" 的值 = 保持库中原值不修改。
 */
import request from './request'

/** 服务列表：[{id, name, transport, url, ..., status, toolCount, enabled}] */
export function listMcpServers() {
  return request.get('/api/mcp/servers')
}

/** 新建服务（校验失败/重名 reject；启用则立即连通，失败仅落 ERROR 状态不回滚保存） */
export function createMcpServer(data) {
  return request.post('/api/mcp/servers', data)
}

/** 编辑服务（name 不可改；保存后热重建客户端，下一轮对话生效） */
export function updateMcpServer(id, data) {
  return request.put(`/api/mcp/servers/${id}`, data)
}

/** 删除服务（关客户端 + 删行；yml 种子行也可删，删即彻底移除） */
export function deleteMcpServer(id) {
  return request.delete(`/api/mcp/servers/${id}`)
}

/** 启停（启用 → 立即连通；停用 → 关客户端，工具即从 Agent 清单消失） */
export function toggleMcpServer(id, enabled) {
  return request.put(`/api/mcp/servers/${id}/toggle`, null, {
    params: { enabled }
  })
}

/**
 * 连接测试（不落库）：编辑场景传 id，后端凭据缺省回退库中值；
 * 永远返回结构化结果 {ok, toolCount, toolNames, elapsedMs, error}，不抛错。
 */
export function testMcpServer(data, id = null) {
  return request.post('/api/mcp/servers/test', data, {
    params: id ? { id } : {}
  })
}

/** 工具预览（读运行时注册表；未连接返回空清单） */
export function listMcpTools(id) {
  return request.get(`/api/mcp/servers/${id}/tools`)
}

/** 手动重连（关旧客户端 → 按当前配置重连并刷新状态） */
export function syncMcpServer(id) {
  return request.post(`/api/mcp/servers/${id}/sync`)
}

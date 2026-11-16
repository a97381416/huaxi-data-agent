/**
 * 对话模型接口封装：
 * - /agent/models        对话页模型选择器（登录即可，仅启用模型最小字段）
 * - /api/models          管理端 CRUD（admin，对应后端 ModelController，仿 mcp.js）
 *
 * request 封装已处理 Authorization Bearer 头 / R<T> 解包 / 401 跳转，此处只声明端点。
 *
 * 凭据契约：后端脱敏回传（apiKey → sk-***b4 形态，同 MCP 服务管理口径）；
 * 编辑时原样回传含 "***"（或留空）的值 = 保持库中原值不修改。
 */
import request from './request'

// ── 对话页（选择器） ──

/** 启用中的模型清单：[{code, name, provider, isDefault, remark}]（按 sort 排序） */
export function listChatModels() {
  return request.get('/agent/models')
}

// ── 管理端（/api/models） ──

/** 模型列表（含 api-key 脱敏值/默认标记/最近测试结果） */
export function listModels() {
  return request.get('/api/models')
}

/** 新建模型（校验失败/code 重复 reject；首行或显式 isDefault 即成为默认） */
export function createModel(data) {
  return request.post('/api/models', data)
}

/** 编辑模型（code 不可改；保存后热生效，下一轮对话可用） */
export function updateModel(id, data) {
  return request.put(`/api/models/${id}`, data)
}

/** 删除模型（删的是默认行则自动移交 sort 最小的启用行） */
export function deleteModel(id) {
  return request.delete(`/api/models/${id}`)
}

/** 启停（停用默认行自动移交默认；停用后即从对话页选择器消失） */
export function toggleModel(id, enabled) {
  return request.put(`/api/models/${id}/toggle`, null, {
    params: { enabled }
  })
}

/** 设为默认模型（须已启用；全局唯一默认） */
export function setDefaultModel(id) {
  return request.put(`/api/models/${id}/default`)
}

/**
 * 连通测试（不落缓存）：编辑场景传 id，后端凭据缺省回退库中值；
 * 永远返回结构化结果 {ok, reply, elapsedMs, error}，不抛错。
 */
export function testModel(data, id = null) {
  return request.post('/api/models/test', data, {
    params: id ? { id } : {}
  })
}

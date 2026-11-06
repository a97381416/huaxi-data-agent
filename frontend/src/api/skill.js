/**
 * /api/skills 技能管理接口（admin 专用，对应后端 SkillController）。
 * request 封装已处理 satoken 头 / R<T> 解包 / 401 跳转，此处只声明端点。
 */
import request from './request'

/** 技能列表：[{name, path, description, enabled}] */
export function listSkills() {
  return request.get('/api/skills')
}

/** 上传技能 zip（新技能默认启用，覆盖上传保留原状态） */
export function uploadSkillZip(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/api/skills/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 切换技能启用/禁用（停用立即从 Agent 工具清单消失） */
export function toggleSkill(name, enabled) {
  return request.put(`/api/skills/${encodeURIComponent(name)}/toggle`, null, {
    params: { enabled }
  })
}

/** 删除技能（目录 + DB 记录） */
export function deleteSkill(name) {
  return request.delete(`/api/skills/${encodeURIComponent(name)}`)
}

/** 技能详情：SKILL.md 全文原文 {name, description, enabled, source, content} */
export function getSkillContent(name) {
  return request.get(`/api/skills/${encodeURIComponent(name)}/content`)
}

/** 手动新建技能（表单：name + description + 正文；后端自动拼 frontmatter） */
export function createSkill(data) {
  return request.post('/api/skills', data)
}

/** 编辑技能内容（内置技能拒绝，手动/上传技能可编辑；description + content） */
export function updateSkillContent(name, data) {
  return request.put(`/api/skills/${encodeURIComponent(name)}/content`, data)
}

/**
 * 导出技能 zip（含 SKILL.md 与全部附属资源）并触发浏览器下载。
 * 走 axios 拉 Blob 以携带 satoken 头（拦截器对无 code 字段的响应原样放行）。
 */
export async function exportSkillZip(name) {
  const blob = await request.get(`/api/skills/${encodeURIComponent(name)}/export`, {
    responseType: 'blob'
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}.zip`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

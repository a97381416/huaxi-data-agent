/**
 * /sys/** 用户/组织管理接口（admin 专用，对应后端 SysUserController）。
 * request 封装已处理 satoken 头 / R<T> 解包 / 401 跳转，此处只声明端点。
 */
import request from './request'

/** 用户列表：page 从 1 起，keyword 模糊 username/nickname。返回 MP Page {records,total,current,size} */
export function listUsers(page = 1, size = 10, keyword = '') {
  return request.get('/sys/user/list', { params: { page, size, keyword: keyword || undefined } })
}

/** 新建用户（角色多选 + 部门多选 + 档案），返回新用户 id */
export function createUser(data) {
  return request.post('/sys/user', data)
}

/** 编辑用户（username 不可改；字段非空才更新） */
export function updateUser(id, data) {
  return request.put(`/sys/user/${id}`, data)
}

/** 删除用户（内置 admin/最后 admin 会被后端拒绝） */
export function deleteUser(id) {
  return request.delete(`/sys/user/${id}`)
}

/** 全量替换用户角色 */
export function updateUserRoles(id, roleIds) {
  return request.put(`/sys/user/${id}/roles`, { roleIds })
}

/** 切换状态（DISABLED 即踢下线） */
export function updateUserStatus(id, status) {
  return request.put(`/sys/user/${id}/status`, { status })
}

/** 全量替换用户部门 */
export function updateUserDepts(id, deptIds) {
  return request.put(`/sys/user/${id}/depts`, { deptIds })
}

/** 重置密码（BCrypt 入库） */
export function resetPassword(userId, newPassword) {
  return request.post('/sys/user/reset-password', { userId, newPassword })
}

/** ACTIVE 角色列表（sort 升序，含 dataScope） */
export function listRoles() {
  return request.get('/sys/role/list')
}

/** 部门树（children 嵌套，el-tree data 直接可用） */
export function getDeptTree() {
  return request.get('/sys/dept/tree')
}

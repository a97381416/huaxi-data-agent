import request from './request'

/** 登录，成功返回 LoginUserVO（含 token/roles/depts/dataScope/profile） */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 登出 */
export function logout() {
  return request.post('/auth/logout', {})
}

/** 当前登录用户完整信息 */
export function fetchMe() {
  return request.get('/auth/me')
}

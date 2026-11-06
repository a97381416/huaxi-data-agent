/**
 * axios 统一封装（对齐后端 R<T> 契约与原版 common.js 行为）：
 * - 请求自动带 satoken header
 * - 响应拦截器解包 R<T>：code=200 直接返回 data；业务失败弹错并 reject
 * - 401（HTTP 或业务码）统一清 token 跳登录页
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'

const request = axios.create({
  baseURL: '',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers['satoken'] = userStore.token
  }
  return config
})

function handleUnauthorized() {
  const userStore = useUserStore()
  userStore.clear()
  ElMessage.warning('未登录或登录已过期')
  router.push({ path: '/login' })
}

request.interceptors.response.use(
  (resp) => {
    const r = resp.data
    // R<T> 结构：{ code, msg, data }
    if (r && typeof r.code === 'number') {
      if (r.code === 200) {
        return r.data
      }
      if (r.code === 401) {
        handleUnauthorized()
        return Promise.reject(new Error(r.msg || '未认证'))
      }
      ElMessage.error(r.msg || '请求失败')
      return Promise.reject(new Error(r.msg || `业务错误 ${r.code}`))
    }
    return r
  },
  (err) => {
    if (err.response && err.response.status === 401) {
      handleUnauthorized()
      return Promise.reject(err)
    }
    // 非 401 的 HTTP 错误（403/409/500…）：优先透出后端 R<T> 的 msg（如"账号已被禁用"）
    const r = err.response?.data
    const msg =
      r && typeof r.code === 'number' && r.msg
        ? r.msg
        : err.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    // reject 携带后端消息，调用方 catch(e).message 可直接展示
    return Promise.reject(new Error(msg))
  }
)

export default request

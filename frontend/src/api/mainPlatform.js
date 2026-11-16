/**
 * 主平台直连 API（M-E1，计划 §3.5 D4）：
 * 业务文件**必须直传主平台** `POST /admin-api/infra/file/upload`（不走 agent MinIO）——
 * doc_file_entry.file_url 落库转相对路径、读取预签名还原（FileUrlBuilder），
 * agent MinIO 的 URL 主平台剥不出相对路径，入库后预览全断。
 *
 * - 凭证：复用登录态 token（与主平台 system_oauth2_access_token 同表同校验）
 * - 响应是主平台 CommonResult：code === 0 成功（注意与 agent 后端的 R.code===200 不同）
 * - 路径 /admin-api 走 vite 代理（dev）/ nginx 同域（生产，M-H4 规划），无 CORS 问题
 */
import axios from 'axios'
import { useUserStore } from '@/stores/user'

/**
 * 上传业务文件到主平台文件服务。
 * @param {File} file 浏览器文件对象
 * @returns {Promise<string>} 文件访问 URL（相对路径形态，可直接绑定进台账条目）
 */
export async function uploadToMainPlatform(file) {
  const userStore = useUserStore()
  const fd = new FormData()
  fd.append('file', file)
  const resp = await axios.post('/admin-api/infra/file/upload', fd, {
    headers: {
      'Content-Type': 'multipart/form-data',
      Authorization: `Bearer ${userStore.token || ''}`
    },
    timeout: 120000 // 大文件上传放宽超时
  })
  const r = resp.data
  if (r && typeof r.code === 'number') {
    if (r.code === 0 && r.data) {
      return r.data
    }
    if (r.code === 401) {
      throw new Error('登录已过期，请重新登录后再上传')
    }
    throw new Error(r.msg || `主平台上传失败（code=${r.code}）`)
  }
  throw new Error('主平台返回格式异常')
}

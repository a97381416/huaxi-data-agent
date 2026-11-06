/**
 * /api/files/* 接口封装（M5）：上传（带进度）/ 删除 / 预览地址。
 */
import request from '@/api/request'

/**
 * 上传单个文件（multipart），返回 {fileId, fileName, fileType, fileSize, status, embed}。
 *
 * @param {File} file 文件对象
 * @param {(percent: number) => void} [onProgress] 上传进度回调（0-100）
 */
export function uploadFile(file, onProgress) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/api/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000, // 大文件上传 + Tika 解析，放宽超时
    onUploadProgress(e) {
      if (e.total && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    }
  })
}

/** 删除文件（先 MinIO 后 DB） */
export function deleteFile(fileId) {
  return request.delete(`/api/files/${encodeURIComponent(fileId)}`)
}

/** 预览地址（后端按扩展名映射 MIME + 7 天缓存；图片可直接 <img> 引用） */
export function filePreviewUrl(fileId) {
  return `/api/files/${encodeURIComponent(fileId)}/preview`
}

/** 图片扩展名判断（附件缩略图渲染用） */
export function isImageType(fileType) {
  return ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'].includes((fileType || '').toLowerCase())
}

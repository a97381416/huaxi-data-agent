/**
 * /agent/* 与 /session/* 接口封装（M3）：
 * - openAgentStream：POST SSE 消费（fetch ReadableStream，对齐原版 chat.js 契约），
 *   事件名 = 事件类型小写，id = 事件自增序号（断线续传游标），data = AgentStreamEvent JSON
 * - stopAgent / getAgentStreamStatus：打断、进行中探测
 * - listSessions / getSessionDetail / deleteSession：会话历史管理
 */
import request from '@/api/request'
import { useUserStore } from '@/stores/user'

/**
 * 发起一条 SSE 流（首发 / recovery 续传共用）。
 * 同步返回 abort 句柄（AbortController）：停止按钮/切会话可立即取消本地读取；
 * 服务端流由 LiveStreamRegistry 后台驱动，取消订阅不影响执行。
 *
 * @param {object} body 请求体（{query, conversationId} 或 {conversationId, recovery, startSeq}）
 * @param {(evt: {event: string, data: object, id: string}) => void} onEvent 事件回调
 * @param {{onError?: (err: Error) => void, onClose?: () => void}} [handlers] 错误/收尾回调
 * @returns {{abort: () => void}} 中断句柄
 */
export function openAgentStream(body, onEvent, handlers = {}) {
  const userStore = useUserStore()
  const ctrl = new AbortController()
  ;(async () => {
    try {
      const resp = await fetch('/agent/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          satoken: userStore.token || ''
        },
        body: JSON.stringify(body),
        signal: ctrl.signal
      })

      if (!resp.ok) {
        const err = new Error(await readErrorMessage(resp))
        err.status = resp.status
        handlers.onError?.(err)
        handlers.onClose?.()
        return
      }
      const contentType = resp.headers.get('content-type') || ''
      if (!contentType.includes('text/event-stream')) {
        handlers.onError?.(new Error('响应不是 SSE 流：' + contentType))
        handlers.onClose?.()
        return
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // 统一换行符后按空行切分 SSE 事件
        buffer = buffer.replace(/\r\n/g, '\n')
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const raw = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          const evt = parseSseEvent(raw)
          if (evt) onEvent(evt)
        }
      }
      handlers.onClose?.()
    } catch (err) {
      handlers.onError?.(err)
      handlers.onClose?.()
    }
  })()
  return { abort: () => ctrl.abort() }
}

async function readErrorMessage(resp) {
  let msg = `请求失败（HTTP ${resp.status}）`
  try {
    const j = await resp.json()
    if (j && j.msg) msg = j.msg
  } catch {
    /* 非 JSON 错误体，保留默认提示 */
  }
  return msg
}

/** 解析单个 SSE 块（event: xxx + id: N + data: {...}） */
function parseSseEvent(raw) {
  let event = 'message'
  let id = ''
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('id:')) {
      id = line.slice(3).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return null
  let payload
  try {
    payload = JSON.parse(dataLines.join('\n'))
  } catch {
    payload = { type: 'TEXT', content: dataLines.join('\n') }
  }
  return { event, data: payload, id }
}

/** 打断运行中回答（后端 interrupt → Paused + 快照落库） */
export function stopAgent(conversationId) {
  return request.get('/agent/stop', { params: { conversationId } })
}

/** 探测会话是否有进行中的流（进页面/切会话时决定是否发 recovery） */
export function getAgentStreamStatus(conversationId) {
  return request.get('/agent/stream/status', { params: { conversationId } })
}

/** Agent 能力探测（M6）：{onlineAvailable} 为 false 表示后端未配 tavily.api-key，联网开关应置灰 */
export function getAgentCapabilities() {
  return request.get('/agent/capabilities')
}

/** 会话列表分页（{conversations, total, page, size, hasMore}） */
export function listSessions(page = 0, size = 10) {
  return request.get('/session/list', { params: { page, size } })
}

/** 会话详情（{conversationId, messages: [{id, question, answer, timeline, createdAt, attachments}]}） */
export function getSessionDetail(conversationId) {
  return request.get(`/session/${encodeURIComponent(conversationId)}`)
}

/** 删除会话（级联消息行与中断快照） */
export function deleteSession(conversationId) {
  return request.delete(`/session/${encodeURIComponent(conversationId)}`)
}

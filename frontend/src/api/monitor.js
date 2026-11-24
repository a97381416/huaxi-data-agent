/**
 * /monitor/* 监控中心接口封装（M3）：总览/提问列表/窗口详情/单轮明细/搜索/运维。
 * AI 诊断 SSE（openDiagnoseStream）在 M5 提供；提问维度切换（2026-11-19）：
 * 列表主视图 = 一次提问一行（/monitor/sessions），诊断支持 sessionId 聚焦单提问。
 */
import request from '@/api/request'
import { useUserStore } from '@/stores/user'

/** 总览（KPI + 趋势 + 失败 TOP + 进行中提问）；range: 1h | 24h | 7d */
export function fetchOverview(range) {
  return request.get('/monitor/overview', { params: { range }, timeout: 60000 })
}

/** 提问列表（服务端分页，一行 = 一次提问）：userId/sessionId/conversationId/status/minMs/maxMs/start/end/q/minRounds/maxRounds/riskOnly/severity/ruleId */
export function fetchSessions(params) {
  return request.get('/monitor/sessions', { params, timeout: 60000 })
}

/** 窗口详情（窗口聚合 + 全部提问 + 轮次瘦字段列表） */
export function fetchConversationDetail(conversationId) {
  return request.get(`/monitor/conversations/${encodeURIComponent(conversationId)}`, { timeout: 60000 })
}

/** 单问最终回答全文（列表「最终回答」弹窗渲染 MD；列表 answerPreview 仅 160 字截断） */
export function fetchSessionAnswer(sessionId) {
  return request.get(`/monitor/sessions/${encodeURIComponent(sessionId)}/answer`, { timeout: 60000 })
}

/**
 * 提问事件流时间轴（2026-11-20 排查视角改造）：❓提问 → [🤖 LLM 决策 + 🔧 并发工具]×N → 💬 终答。
 * llm 事件带增量消息（本轮新传入上下文）；工具结果按 tool_call_id 从下一轮 INPUT 配对（resultRefSeq 指向）。
 * 全量 INPUT 等重字段不走本接口，前端按需 fetchRoundDetail 懒加载。
 */
export function fetchSessionTimeline(sessionId) {
  return request.get(`/monitor/sessions/${encodeURIComponent(sessionId)}/timeline`, { timeout: 60000 })
}

/** 单轮明细（payload 懒加载；includeTools 对齐 TraceQueryService 渲染契约） */
export function fetchRoundDetail(traceId, includeTools) {
  return request.get(`/monitor/rounds/${traceId}`, { params: { includeTools }, timeout: 60000 })
}

/** 快捷搜索：conversation_id 精确 → session_id 精确 → user_id → question 模糊（提问条目） */
export function searchMonitor(q) {
  return request.get('/monitor/search', { params: { q } })
}

/** 运维操作：running 会话置为 interrupted（调用前二次确认） */
export function interruptConversation(conversationId) {
  return request.post(`/monitor/conversations/${encodeURIComponent(conversationId)}/interrupt`)
}

// ==================== Step3 找到异常（03 方案 §5） ====================

/** 风险列表（详情页自动检测卡数据源；severity desc, round asc） */
export function fetchSessionRisks(sessionId) {
  return request.get(`/monitor/sessions/${encodeURIComponent(sessionId)}/risks`, { timeout: 60000 })
}

/** 该提问各规则已有诊断报告（规则卡「已有报告」角标；无则空数组） */
export function fetchSessionRiskReports(sessionId) {
  return request.get(`/monitor/sessions/${encodeURIComponent(sessionId)}/risk-reports`, { timeout: 30000 })
}

/** 手动重检（详情页「重新检测/立即检测」按钮；同步执行 <1s 量级） */
export function triggerDetect(sessionId) {
  return request.post(`/monitor/sessions/${encodeURIComponent(sessionId)}/detect`, {}, { timeout: 60000 })
}

/** 规则注册表（「规则 N 条」弹层数据源）：{ version, rules: [...] } */
export function fetchDetectRules() {
  return request.get('/monitor/detect/rules', { timeout: 30000 })
}

/** 改单条规则 enabled/params（即时生效，返回合并后配置供回显） */
export function updateDetectRule(ruleId, payload) {
  return request.put(`/monitor/detect/rules/${encodeURIComponent(ruleId)}`, payload, { timeout: 30000 })
}

/**
 * 批量重诊断（V2，只读回放）：按 conversationIds / detectorVersionLt / from-to 圈定历史提问
 * 重跑 v2 规则（不重放工具）。返回 { accepted, skipped }。
 */
export function rerunDetect(payload) {
  return request.post('/monitor/detect/rerun', payload, { timeout: 60000 })
}

/** 批量重诊断进度（内存计数：accepted/completed/failed/pending） */
export function fetchRerunStatus() {
  return request.get('/monitor/detect/rerun/status', { timeout: 30000 })
}

/**
 * 报告中心（M5）：列表 + 详情。
 */
export function fetchReports(params) {
  return request.get('/monitor/reports', { params })
}

export function fetchReportDetail(id) {
  return request.get(`/monitor/reports/${id}`)
}

/**
 * 触发 AI 诊断（M5，POST SSE）：事件 thinking / tool_call / stage / report_id / done / error，
 * 事件名 = 类型小写，data = JSON。fetch ReadableStream 消费（同 openAgentStream 模式）。
 * sessionId 非空 = 提问级诊断（聚焦该提问）；为空 = 窗口级全量体检。
 * ruleId 非空 = 规则级定向诊断（风险卡「AI 诊断此风险」，规则证据后端自动注入，须带 sessionId）。
 * model 选哪个模型执行诊断（清单同聊天侧 listChatModels）：空 = 诊断默认（yml 专用诊断模型）；
 * 非空 = agentx_chat_model 的 code（后端解析语义同 DodoAgent：空/失效回退）。
 */
export function openDiagnoseStream(conversationId, onEvent, handlers = {}, question = '', sessionId = '', ruleId = '', model = '') {
  const userStore = useUserStore()
  const ctrl = new AbortController()
  ;(async () => {
    try {
      const ps = new URLSearchParams()
      if (sessionId && sessionId.trim()) ps.set('sessionId', sessionId.trim())
      if (question && question.trim()) ps.set('question', question.trim())
      if (ruleId && ruleId.trim()) ps.set('ruleId', ruleId.trim())
      if (model && model.trim()) ps.set('model', model.trim())
      const qs = ps.toString() ? `?${ps.toString()}` : ''
      const resp = await fetch(`/monitor/diagnose/${encodeURIComponent(conversationId)}${qs}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${userStore.token || ''}`
        },
        body: '{}',
        signal: ctrl.signal
      })
      if (!resp.ok) {
        let msg = `请求失败（HTTP ${resp.status}）`
        try {
          const j = await resp.json()
          if (j && j.msg) msg = j.msg
        } catch {
          /* 非 JSON 错误体 */
        }
        handlers.onError?.(new Error(msg))
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
      if (err.name !== 'AbortError') handlers.onError?.(err)
      handlers.onClose?.()
    }
  })()
  return { abort: () => ctrl.abort() }
}

/** 解析一条 SSE 原始事件块（event/data 行） */
function parseSseEvent(raw) {
  let event = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return null
  let data = null
  try {
    data = JSON.parse(dataLines.join('\n'))
  } catch {
    data = dataLines.join('\n')
  }
  return { event, data }
}

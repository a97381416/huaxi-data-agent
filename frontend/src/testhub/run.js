/**
 * 测试运行引擎：执行场景里的 auto 定义（http 步骤序列 / sse 流断言 / sse+stop 打断断言）。
 *
 * 设计要点：
 * - 全部走原生 fetch（不走 axios 封装）——测试需要观察原始 HTTP 状态码与 R 响应体，
 *   不能被 axios 拦截器的 401 跳转/自动弹错干扰。
 * - 里程碑未交付时接口 404/405 → 结果记为 blocked（未交付），不算失败。
 * - {{token:admin}} 模板：懒登录测试账号并缓存 token；{{tokenFresh:x}} 强制新登录；
 *   {{var:name}} 取上下文变量；{{convId}}/{{uuid}} 生成会话号。
 */
import { useUserStore } from '@/stores/user'
import { evaluate } from './assert'

const ACCOUNT_PASSWORDS = {
  admin: '123456',
  mgr_test: '123456',
  analyst_test: '123456',
  sh_sales: '123456',
  cross_analyst: '123456'
}

export function newContext() {
  return { tokens: {}, vars: {}, last: null }
}

async function apiLogin(account) {
  const resp = await fetch('/auth/login', {
    method: 'POST',
    cache: 'no-store', credentials: 'omit',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: account, password: ACCOUNT_PASSWORDS[account] || '123456' })
  })
  const json = await resp.json().catch(() => null)
  if (!json || json.code !== 200 || !json.data?.token) {
    throw new Error(`测试账号 ${account} 登录失败：${json?.msg || `HTTP ${resp.status}`}`)
  }
  return json
}

async function ensureToken(ctx, account) {
  if (!ctx.tokens[account]) {
    const json = await apiLogin(account)
    ctx.tokens[account] = json.data.token
  }
  return ctx.tokens[account]
}

async function resolveString(s, ctx) {
  // 变量名允许点号（如 tmpLogin.token）
  const re = /\{\{\s*(token|tokenFresh|var)\s*:\s*([\w.-]+)\s*\}\}|\{\{\s*(uuid|convId)\s*\}\}/g
  let result = ''
  let last = 0
  let m
  while ((m = re.exec(s))) {
    result += s.slice(last, m.index)
    if (m[1] === 'token') {
      result += await ensureToken(ctx, m[2])
    } else if (m[1] === 'tokenFresh') {
      result += (await apiLogin(m[2])).data.token
    } else if (m[1] === 'var') {
      // 变量名支持点号路径（如 tmpLogin.token）
      result += m[2].split('.').reduce((o, k) => (o === undefined || o === null ? undefined : o[k]), ctx.vars) ?? ''
    } else if (m[3] === 'uuid') {
      result += crypto.randomUUID()
    } else if (!ctx.vars.convId) {
      ctx.vars.convId = 'test_conv_' + crypto.randomUUID()
      result += ctx.vars.convId
    } else {
      result += ctx.vars.convId
    }
    last = re.lastIndex
  }
  return result + s.slice(last)
}

async function resolveValue(v, ctx) {
  if (typeof v === 'string') return resolveString(v, ctx)
  if (Array.isArray(v)) {
    const out = []
    for (const item of v) out.push(await resolveValue(item, ctx))
    return out
  }
  if (v && typeof v === 'object') {
    const out = {}
    for (const [k, val] of Object.entries(v)) out[k] = await resolveValue(val, ctx)
    return out
  }
  return v
}

/** 默认带 UI 登录 token（与 axios 封装一致）；步骤自己声明了 satoken/Authorization 则不覆盖 */
async function buildHeaders(rawHeaders, ctx, noToken) {
  const headers = {}
  const entries = Object.entries(rawHeaders || {})
  const hasAuth = entries.some(([k]) => ['satoken', 'authorization'].includes(k.toLowerCase()))
  for (const [k, v] of entries) headers[k] = await resolveValue(v, ctx)
  if (!noToken && !hasAuth) {
    const uiToken = useUserStore().token
    if (uiToken) headers['satoken'] = uiToken
  }
  return headers
}

/**
 * 里程碑未交付时的"受阻"识别。本项目后端把 404 兜底成 HTTP 200 + R{code:500,"No static resource"}，
 * 所以除了 HTTP 404/405，还要识别 R 业务码 404 与"无此接口"类兜底消息。
 */
function blockedIfUndelivered(resp, url, opt, json) {
  if (!opt || opt.delivered) return
  const msg = json?.msg || ''
  const noHandler =
    resp.status === 404 ||
    resp.status === 405 ||
    json?.code === 404 ||
    (/No static resource|No handler|not found/i.test(msg) && (json?.code === 500 || json?.code === 404))
  if (noHandler) {
    const e = new Error(`接口未交付（${url} → ${json ? `R{code:${json.code}, msg:${msg}}` : `HTTP ${resp.status}`}）——对应里程碑尚未开发，交付后此用例即可真实运行`)
    e.blocked = true
    throw e
  }
}

async function runHttpStep(step, ctx, detail, opt) {
  const url = await resolveValue(step.url, ctx)
  const headers = await buildHeaders(step.headers, ctx, step.noToken)
  let body
  if (step.uploadFile) {
    // 自动构造 multipart 上传（边界由浏览器生成，勿手工设 Content-Type）
    const fd = new FormData()
    fd.append(
      step.uploadFile.field || 'file',
      new File([step.uploadFile.content || 'testhub probe file'], step.uploadFile.filename || 'probe.txt', { type: 'text/plain' })
    )
    body = fd
  } else if (step.body !== undefined) {
    body = await resolveValue(step.body, ctx)
    if (typeof body !== 'string') {
      body = JSON.stringify(body)
      if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
        headers['Content-Type'] = 'application/json'
      }
    }
  }
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), step.timeoutMs || 30000)
  let resp
  try {
    resp = await fetch(url, { method: step.method || 'GET', cache: 'no-store', credentials: 'omit', headers, body, signal: ctrl.signal })
  } finally {
    clearTimeout(timer)
  }
  const text = await resp.text().catch(() => '')
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 响应 */
  }
  detail.steps.push({
    kind: 'http',
    method: step.method || 'GET',
    url,
    status: resp.status,
    contentType: resp.headers.get('content-type') || '',
    request: step.uploadFile ? `(multipart 上传 ${step.uploadFile.filename || 'probe.txt'})` : typeof body === 'string' ? body : '',
    response: text.length > 4000 ? text.slice(0, 4000) + '…(截断)' : text
  })
  ctx.last = { status: resp.status, headers: resp.headers, text, json }
  blockedIfUndelivered(resp, url, opt, json)
  if (step.saveToken && json?.data?.token) ctx.tokens[step.saveToken] = json.data.token
  if (step.saveVar !== undefined) ctx.vars[step.saveVar] = json?.data ?? json
  evaluate(step.expect, { status: resp.status, headers: resp.headers, text, json }, detail)
}

/** 解析 SSE 帧（event:/id:/data:），返回事件数组（含帧序号 id） */
async function consumeSse(respBody, onEvent, ctrl, timeoutMs) {
  const reader = respBody.getReader()
  const decoder = new TextDecoder('utf-8')
  const timer = setTimeout(() => ctrl.abort(), timeoutMs || 120000)
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const lines = frame.split('\n')
        const dataText = lines
          .filter((l) => l.startsWith('data:'))
          .map((l) => l.slice(5).trim())
          .join('\n')
        if (!dataText) continue
        const idLine = lines.find((l) => l.startsWith('id:'))
        let evt
        try {
          evt = JSON.parse(dataText)
        } catch {
          evt = { type: 'RAW', content: dataText }
        }
        onEvent(evt, idLine ? idLine.slice(3).trim() : null)
      }
    }
  } finally {
    clearTimeout(timer)
  }
}

function buildSseGot(events, ids) {
  const terminalEvs = events.filter((e) => ['PAUSED', 'COMPLETE', 'ERROR'].includes(e.type))
  // 工具调用视图：TOOL_START 顺序表 + 按 toolCallId 归并的各工具结果全文（供工具断言用）
  const id2name = {}
  events.forEach((e) => {
    if (e.type === 'TOOL_START') id2name[e.toolCallId] = e.toolName || e.name || '?'
  })
  const toolCalls = []
  const toolResults = {}
  events.forEach((e) => {
    if (e.type === 'TOOL_START') {
      toolCalls.push(e.toolName || e.name || '?')
    } else if (e.type === 'TOOL_END') {
      const name = e.toolName || e.name || id2name[e.toolCallId] || '?'
      ;(toolResults[name] = toolResults[name] || []).push(String(e.result ?? e.output ?? ''))
    }
  })
  return {
    events,
    ids,
    terminal: terminalEvs.length ? terminalEvs[terminalEvs.length - 1].type : null,
    text: events.filter((e) => e.type === 'TEXT').map((e) => e.content || '').join(''),
    toolCalls,
    toolResults: Object.fromEntries(Object.entries(toolResults).map(([k, v]) => [k, v.join('\n')]))
  }
}

function summarizeEvents(detail, events, ids) {
  detail.steps.push({
    kind: 'sse',
    eventCount: events.length,
    events: events.slice(0, 200).map((e, i) => ({
      seq: ids[i] ?? '',
      type: e.type,
      summary: summarizeEvent(e)
    }))
  })
}

function summarizeEvent(e) {
  switch (e.type) {
    case 'THINKING':
      return (e.content || '').slice(0, 60)
    case 'TEXT':
      return (e.content || '').slice(0, 60)
    case 'TOOL_START':
      return `${e.toolName || e.name || '?'} ${String(e.arguments || '').slice(0, 60)}`
    case 'TOOL_END':
      return `${e.toolName || e.name || '?'} → ${String(e.result || e.output || '').slice(0, 80)}`
    case 'ERROR':
      return `${e.message || ''} ${e.detail || ''}`.slice(0, 100)
    default:
      return JSON.stringify(e).slice(0, 100)
  }
}

async function runSseStep(step, ctx, detail, opt) {
  const conf = step.kind === 'sseStop' ? step.sse : step
  const url = await resolveValue(conf.url, ctx)
  const headers = await buildHeaders(conf.headers, ctx, conf.noToken)
  if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
    headers['Content-Type'] = 'application/json'
  }
  const body = await resolveValue(conf.body || {}, ctx)
  const ctrl = new AbortController()
  const resp = await fetch(url, {
    method: 'POST',
    cache: 'no-store', credentials: 'omit',
    headers,
    body: JSON.stringify(body),
    signal: ctrl.signal
  })
  const contentType = resp.headers.get('content-type') || ''
  if (!resp.ok || !resp.body || !contentType.includes('text/event-stream')) {
    // 非 SSE 响应：整体读出（可能是 R{...} 兜底 JSON），记录后再做未交付判定
    const errText = await resp.text().catch(() => '')
    let errJson = null
    try {
      errJson = JSON.parse(errText)
    } catch {
      /* 非 JSON */
    }
    detail.steps.push({ kind: 'http', method: 'POST', url, status: resp.status, contentType, response: errText.slice(0, 1000) })
    blockedIfUndelivered(resp, url, opt, errJson)
    throw new Error(`SSE 请求失败：HTTP ${resp.status}（Content-Type: ${contentType || '空'}）`)
  }

  const events = []
  const ids = []
  const readerDone = consumeSse(resp.body, (evt, id) => { events.push(evt); ids.push(id) }, ctrl, step.timeoutMs || 120000)

  // 打断类用例：流跑 stopAfterMs 后调 stop 接口，再继续消费直到流结束
  let stopRec = null
  if (step.kind === 'sseStop') {
    await sleep(step.stopAfterMs ?? 3000)
    const stopUrl = await resolveValue(step.stop.url, ctx)
    const stopHeaders = await buildHeaders(step.stop.headers, ctx, step.stop.noToken)
    const stopResp = await fetch(stopUrl, { method: step.stop.method || 'GET', cache: 'no-store', credentials: 'omit', headers: stopHeaders })
    const stopText = await stopResp.text().catch(() => '')
    stopRec = {
      kind: 'http',
      method: step.stop.method || 'GET',
      url: stopUrl,
      status: stopResp.status,
      response: stopText.slice(0, 1000)
    }
    detail.steps.push(stopRec)
    // stop 之后给流最多 30s 收尾，超时中止
    const guard = setTimeout(() => ctrl.abort(), 30000)
    await readerDone.finally(() => clearTimeout(guard))
  } else {
    await readerDone
  }

  const got = buildSseGot(events, ids)
  got.contentType = contentType
  summarizeEvents(detail, events, ids)

  // 可选：stop 后查快照接口（M1 /agent/demo/snapshot）
  if (step.snapshot) {
    const snapUrl = await resolveValue(step.snapshot.url, ctx)
    const snapHeaders = await buildHeaders(step.snapshot.headers, ctx, false)
    const snapResp = await fetch(snapUrl, { method: 'GET', cache: 'no-store', credentials: 'omit', headers: snapHeaders })
    const snapText = await snapResp.text().catch(() => '')
    let snapJson = null
    try {
      snapJson = JSON.parse(snapText)
    } catch {
      /* 非 JSON */
    }
    got.snapshot = { status: snapResp.status, json: snapJson }
    detail.steps.push({
      kind: 'http',
      method: 'GET',
      url: snapUrl,
      status: snapResp.status,
      response: snapText.slice(0, 1000)
    })
  }

  evaluate(step.expect, got, detail)
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

/**
 * 执行一个场景的 auto 定义。
 * @returns {{state:'passed'|'failed'|'blocked', message:string, detail:{steps:Array, expectations:Array}}}
 */
export async function runScenario(scenario, ctx, opt = {}) {
  const detail = { steps: [], expectations: [] }
  try {
    for (const step of scenario.auto.steps) {
      if (step.kind === 'sse' || step.kind === 'sseStop') {
        await runSseStep(step, ctx, detail, opt)
      } else {
        await runHttpStep(step, ctx, detail, opt)
      }
    }
    return { state: 'passed', message: '全部断言通过', detail }
  } catch (e) {
    if (e.name === 'AbortError') {
      return { state: 'failed', message: '请求/流超时被中止（timeoutMs 可在场景定义中调整）', detail }
    }
    const state = e.blocked ? 'blocked' : 'failed'
    return { state, message: e.blocked ? e.message : e.message || '执行异常', detail }
  }
}

/**
 * 断言助手：对 http 步骤结果（status/json/text）与 sse 步骤结果（events/terminal/text）做声明式校验。
 * 每条断言产出 { pass, msg }，msg 直接展示在用例详情里。
 */

/** 简易取值：支持 a.b[0].c 形式的路径；空路径返回整个对象（用于根级字段检查） */
export function getJsonPath(obj, path) {
  if (path === undefined || path === null || path === '') return obj
  const segs = path
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .filter((s) => s !== '')
  let cur = obj
  for (const s of segs) {
    if (cur === null || cur === undefined) return undefined
    cur = cur[s]
  }
  return cur
}

function fmt(v) {
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/**
 * @param exp 声明式断言，kind 见分支
 * @param got http: {status, headers, text, json}；sse: {events, terminal, text, ids, snapshot?}
 */
export function checkExp(exp, got) {
  const k = exp.kind
  switch (k) {
    // ---- HTTP / 通用 ----
    case 'status':
      return { pass: got.status === exp.value, msg: `HTTP 状态码 = ${got.status}（期望 ${exp.value}）` }
    case 'statusNot':
      return { pass: got.status !== exp.value, msg: `HTTP 状态码 = ${got.status}（期望 ≠ ${exp.value}）` }
    case 'statusIn':
      return { pass: exp.value.includes(got.status), msg: `HTTP 状态码 = ${got.status}（期望 ∈ [${exp.value.join(', ')}]）` }
    case 'bodyCode':
      return { pass: got.json?.code === exp.value, msg: `业务码 code = ${got.json?.code}（期望 ${exp.value}）` }
    case 'bodyCodeNot':
      return { pass: got.json?.code !== exp.value, msg: `业务码 code = ${got.json?.code}（期望 ≠ ${exp.value}）` }
    case 'msgIncludes':
      return { pass: (got.json?.msg || '').includes(exp.value), msg: `msg 含"${exp.value}"（实际：${got.json?.msg || '空'}）` }
    case 'jsonTrue':
      return { pass: !!getJsonPath(got.json, exp.path), msg: `${exp.path} 应为真值（实际：${fmt(getJsonPath(got.json, exp.path))}）` }
    case 'jsonEq':
      return { pass: getJsonPath(got.json, exp.path) === exp.value, msg: `${exp.path} = ${fmt(getJsonPath(got.json, exp.path))}（期望 ${fmt(exp.value)}）` }
    case 'jsonIsString':
      return { pass: typeof getJsonPath(got.json, exp.path) === 'string', msg: `${exp.path} 应为字符串（实际类型 ${typeof getJsonPath(got.json, exp.path)}）` }
    case 'jsonMatch': {
      const v = getJsonPath(got.json, exp.path)
      return { pass: typeof v === 'string' && new RegExp(exp.value).test(v), msg: `${exp.path} 匹配 /${exp.value}/（实际：${fmt(v)?.slice(0, 80)}）` }
    }
    case 'jsonLen': {
      const v = getJsonPath(got.json, exp.path)
      const n = Array.isArray(v) ? v.length : -1
      const min = exp.min ?? 0
      const max = exp.max ?? Number.MAX_SAFE_INTEGER
      return { pass: n >= min && n <= max, msg: `${exp.path} 数组长度 = ${n}（期望 ${min}~${max}）` }
    }
    case 'jsonArrayHas': {
      const v = getJsonPath(got.json, exp.path)
      const has = Array.isArray(v) && v.includes(exp.value)
      return { pass: has, msg: `${exp.path} 应包含 "${exp.value}"（实际：${Array.isArray(v) ? `[${v.slice(0, 8).join(', ')}${v.length > 8 ? ',…' : ''}]` : '非数组'}）` }
    }
    case 'jsonArrayNotHas': {
      const v = getJsonPath(got.json, exp.path)
      const has = Array.isArray(v) && v.includes(exp.value)
      return { pass: Array.isArray(v) && !has, msg: `${exp.path} 不应包含 "${exp.value}"（实际：${Array.isArray(v) ? `[${v.slice(0, 8).join(', ')}${v.length > 8 ? ',…' : ''}]` : '非数组'}）` }
    }
    case 'jsonNumMin': {
      const v = Number(getJsonPath(got.json, exp.path))
      return { pass: Number.isFinite(v) && v >= exp.min, msg: `${exp.path} = ${getJsonPath(got.json, exp.path)}（期望 ≥ ${exp.min}）` }
    }
    case 'jsonKeys': {
      const v = getJsonPath(got.json, exp.path) || {}
      const missing = exp.value.filter((key) => !(key in v))
      return { pass: missing.length === 0, msg: `${exp.path} 应含字段 [${exp.value.join(', ')}]${missing.length ? `，缺 ${missing.join(', ')}` : ''}` }
    }
    case 'jsonSome': {
      // 数组任一元素的 field 子路径满足全部给定条件（eq/min/match/lenMin，可组合）——
      // 用于下标不确定的嵌套结构（如 timeline.events 中挂载 subSession 的委派 tool 事件，M-TT）
      const arr = getJsonPath(got.json, exp.path)
      if (!Array.isArray(arr)) return { pass: false, msg: `${exp.path} 应为数组（实际：${typeof arr}）` }
      const pick = (o, f) => f.split('.').reduce((v, k) => (v == null ? undefined : v[k]), o)
      const conds = []
      if (exp.eq !== undefined) conds.push(`eq ${fmt(exp.eq)}`)
      if (exp.min !== undefined) conds.push(`min ${exp.min}`)
      if (exp.match !== undefined) conds.push(`match /${exp.match}/`)
      if (exp.lenMin !== undefined) conds.push(`lenMin ${exp.lenMin}`)
      for (const el of arr) {
        const v = exp.field ? pick(el, exp.field) : el
        let ok = true
        if (exp.eq !== undefined) ok = ok && v === exp.eq
        if (exp.min !== undefined) ok = ok && Number(v) >= exp.min
        if (exp.match !== undefined) {
          const s = typeof v === 'string' ? v : JSON.stringify(v ?? '')
          ok = ok && new RegExp(exp.match).test(s)
        }
        if (exp.lenMin !== undefined) ok = ok && Array.isArray(v) && v.length >= exp.lenMin
        if (ok) return { pass: true, msg: `${exp.path} 存在元素满足 ${exp.field || '元素'} ${conds.join(' 且 ')}` }
      }
      return { pass: false, msg: `${exp.path} 无任何元素满足 ${exp.field || '元素'} ${conds.join(' 且 ')}（共 ${arr.length} 个元素）` }
    }
    case 'contentTypeIncludes':
      return { pass: (got.headers?.get?.('content-type') || got.contentType || '').includes(exp.value), msg: `Content-Type 含 "${exp.value}"` }

    // ---- SSE 流 ----
    case 'eventsMin':
      return { pass: got.events.length >= exp.min, msg: `SSE 事件数 ≥ ${exp.min}（实际 ${got.events.length}）` }
    case 'terminal':
      return { pass: got.terminal === exp.value, msg: `流以 ${exp.value} 收尾（实际终态：${got.terminal || '无'}）` }
    case 'noEventType': {
      const n = got.events.filter((e) => e.type === exp.value).length
      return { pass: n === 0, msg: `不应出现 ${exp.value} 事件（实际 ${n} 次）` }
    }
    case 'eventTypeHas':
      return { pass: got.events.some((e) => e.type === exp.value), msg: `应出现 ${exp.value} 事件` }
    case 'eventTypeMin': {
      const n = got.events.filter((e) => e.type === exp.type).length
      return { pass: n >= exp.min, msg: `${exp.type} 事件 ≥ ${exp.min} 次（实际 ${n} 次）` }
    }
    case 'orderContains': {
      // 各事件类型"首次出现位置"应按给定顺序（允许中间夹杂其他类型）
      const first = new Map()
      got.events.forEach((e, i) => { if (!first.has(e.type)) first.set(e.type, i) })
      let cursor = -1
      for (const t of exp.value) {
        const at = first.get(t)
        if (at === undefined) return { pass: false, msg: `事件首次出现顺序缺少 ${t}` }
        if (at < cursor) return { pass: false, msg: `事件顺序不符：${t} 首次出现在前序类型之前` }
        cursor = at
      }
      return { pass: true, msg: `事件首次出现顺序包含 [${exp.value.join(' → ')}]` }
    }
    case 'firstAppearOrder': {
      const seq = [...new Set(got.events.map((e) => e.type))]
      return { pass: seq.join(',') === exp.value.join(','), msg: `事件首现序列 = [${seq.join(' → ')}]（期望 [${exp.value.join(' → ')}]）` }
    }
    case 'toolNameCalled':
      return { pass: got.events.some((e) => e.type === 'TOOL_START' && (e.toolName === exp.value || e.name === exp.value)), msg: `应调用工具 ${exp.value}` }
    case 'toolCalledAny': {
      const names = exp.value || []
      const hit = (got.toolCalls || []).some((n) => names.includes(n))
      return { pass: hit, msg: `应调用工具 [${names.join(' / ')}] 之一（实际调用：[${(got.toolCalls || []).join(' → ')}]）` }
    }
    case 'toolCallCountMin': {
      const n = (got.toolCalls || []).filter((x) => x === exp.tool).length
      return { pass: n >= exp.min, msg: `工具 ${exp.tool} 调用 ≥ ${exp.min} 次（实际 ${n} 次）` }
    }
    case 'toolResultIncludes': {
      const tools = exp.tools || [exp.tool]
      const text = tools.map((t) => (got.toolResults || {})[t] || '').join('\n')
      return { pass: new RegExp(exp.value).test(text), msg: `${tools.join('/')} 结果应匹配 /${exp.value}/（实际片段：${text.slice(0, 120) || '（无工具结果）'}）` }
    }
    case 'toolOrderContains': {
      // 各工具"首次调用位置"应按给定顺序（允许夹杂其他工具）
      const calls = got.toolCalls || []
      const first = new Map()
      calls.forEach((n, i) => { if (!first.has(n)) first.set(n, i) })
      let cursor = -1
      for (const t of exp.value) {
        const at = first.get(t)
        if (at === undefined) return { pass: false, msg: `工具调用顺序缺少 ${t}（实际：[${calls.join(' → ')}]）` }
        if (at < cursor) return { pass: false, msg: `工具调用顺序不符：${t} 首次出现在前序工具之前（实际：[${calls.join(' → ')}]）` }
        cursor = at
      }
      return { pass: true, msg: `工具首现顺序包含 [${exp.value.join(' → ')}]` }
    }
    case 'firstToolIs': {
      const first = (got.toolCalls || [])[0]
      return { pass: first === exp.value, msg: `首个调用的工具应为 ${exp.value}（实际：${first || '无工具调用'}）` }
    }
    case 'textIncludes':
      return { pass: new RegExp(exp.value).test(got.text || ''), msg: `最终回答应匹配 /${exp.value}/` }
    case 'textExcludes':
      return { pass: !new RegExp(exp.value).test(got.text || ''), msg: `最终回答不应匹配 /${exp.value}/` }
    case 'textLenMin':
      return { pass: (got.text || '').trim().length >= exp.min, msg: `正文文本长度 ≥ ${exp.min}（实际 ${(got.text || '').trim().length}）` }
    case 'idsIncreasing': {
      const ids = got.ids.filter((x) => x !== null && x !== undefined)
      let pass = ids.length > 0
      for (let i = 1; i < ids.length; i++) if (Number(ids[i]) <= Number(ids[i - 1])) pass = false
      return { pass, msg: `SSE id 序号严格递增（捕获 ${ids.length} 个：${ids.slice(0, 5).join(', ')}${ids.length > 5 ? '…' : ''}）` }
    }
    case 'snapshotTrue':
      return { pass: got.snapshot?.json?.data?.hasSnapshot === true, msg: `中断快照 hasSnapshot = ${got.snapshot?.json?.data?.hasSnapshot}` }
    // ---- 多 Agent（M-SA）：子 Agent 事件归属与终态唯一性 ----
    case 'subEventHas':
      return {
        pass: got.events.some((e) => e.parentToolCallId && (!exp.agentCode || e.agentCode === exp.agentCode)),
        msg: `应出现带 parentToolCallId${exp.agentCode ? ' 且 agentCode=' + exp.agentCode : ''} 的子 Agent 事件`
      }
    case 'subToolCalled':
      return {
        pass: got.events.some((e) => e.type === 'TOOL_START' && e.parentToolCallId && (e.toolName === exp.value || e.name === exp.value)),
        msg: `子 Agent 内部应调用工具 ${exp.value}`
      }
    case 'terminalCountEq': {
      const n = got.events.filter((e) => e.type === 'COMPLETE' || e.type === 'PAUSED').length
      const want = exp.value ?? 1
      return { pass: n === want, msg: `COMPLETE/PAUSED 终态事件总数应为 ${want}（实际 ${n}，子 Agent 终态不得外泄）` }
    }    default:
      return { pass: false, msg: `未知断言类型：${k}` }
  }
}

/** 逐条评估；全部记录进 detail.expectations，有失败则抛出首条失败信息 */
export function evaluate(exps, got, detail) {
  let firstFail = ''
  for (const exp of exps || []) {
    const r = checkExp(exp, got)
    detail.expectations.push({ ...r, name: exp.kind })
    if (!r.pass && !firstFail) firstFail = r.msg
  }
  if (firstFail) throw new Error(firstFail)
}

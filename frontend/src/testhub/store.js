/**
 * 测试结果持久化：按里程碑存 localStorage（da-testhub:m{x}），刷新页面不丢。
 * 手工用例的"人工通过/不通过"与自动用例的运行结果都走这里。
 */

const keyOf = (m) => `da-testhub:m${m}`

function loadAll(m) {
  try {
    return JSON.parse(localStorage.getItem(keyOf(m)) || '{}')
  } catch {
    return {}
  }
}

function persist(m, results) {
  localStorage.setItem(keyOf(m), JSON.stringify(results))
}

/** detail 体积控制：事件明细最多留 60 条、文本截断，避免撑爆 localStorage */
function slimDetail(detail) {
  if (!detail) return { steps: [], expectations: [] }
  return {
    expectations: (detail.expectations || []).slice(0, 60),
    steps: (detail.steps || []).slice(0, 20).map((s) =>
      s.kind === 'sse'
        ? { ...s, events: (s.events || []).slice(0, 60) }
        : { ...s, response: (s.response || '').slice(0, 2000), request: (s.request || '').slice(0, 1000) }
    )
  }
}

export function getResult(m, id) {
  return loadAll(m)[id] || null
}

export function saveResult(m, id, result) {
  const all = loadAll(m)
  all[id] = { ...result, detail: slimDetail(result.detail), ts: Date.now() }
  persist(m, all)
}

export function clearResults(m) {
  localStorage.removeItem(keyOf(m))
}

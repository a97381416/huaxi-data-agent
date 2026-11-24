/**
 * JSON 字符串编码文本归一化（提问详情「事件快照」与 MdPreviewDialog 共用）。
 *
 * 背景：tool 消息 content 在链路上被 JSON 字符串编码过一至两层（Spring AI
 * DefaultToolCallResultConverter 对 String 结果再走一次 JsonParser.toJson，
 * 历史轮已落库），直接 MD 渲染是一坨带字面 \n 的原文。这里逐层 JSON.parse 解包：
 * - 解出 JSON 对象/数组 → jsonValue（调用方可给结构化树）
 * - 解出纯字符串 → 继续解包（最多 limit 层防失控）
 * 「原文」语义不受影响（调用方保留原始文本即可）。
 */
export function normalizeJsonText(content, limit = 3) {
  let text = content || ''
  let layers = 0
  let jsonValue = null
  while (layers < limit) {
    const t = text.trim()
    if (!t || !['{', '[', '"'].includes(t[0])) break
    let parsed
    try {
      parsed = JSON.parse(t)
    } catch {
      break
    }
    if (typeof parsed === 'string') {
      text = parsed
      layers++
      continue
    }
    if (parsed && typeof parsed === 'object') {
      jsonValue = parsed
    }
    break
  }
  return { text, jsonValue, layers }
}

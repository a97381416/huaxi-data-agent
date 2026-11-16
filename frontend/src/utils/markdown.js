/**
 * 共享 markdown-it 实例（对话气泡 ChatView + 技能详情 SkillManageView + 评测报告 EvalReportView 共用），
 * 配置与防注入图片规则单处维护，避免多视图配置漂移。
 */
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

// 图表图片：包一层 <a> 新窗口打开原图（容器内 max-width 约束见各视图的 img 样式）
md.renderer.rules.image = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const src = token.attrGet('src') || ''
  const alt = token.content || token.attrGet('alt') || ''
  // 校验 http(s) 协议，防 javascript: 伪协议注入
  if (/^https?:\/\//i.test(src)) {
    return `<a href="${md.utils.escapeHtml(src)}" target="_blank" rel="noopener" title="点击查看原图">` +
      `<img src="${md.utils.escapeHtml(src)}" alt="${md.utils.escapeHtml(alt)}" loading="lazy"></a>`
  }
  return `<img src="${md.utils.escapeHtml(src)}" alt="${md.utils.escapeHtml(alt)}" loading="lazy">`
}

// 表格外层包 .md-table-wrap 承担横向滚动：table 保持原生 display:table，其 width:100% 是
// 「最小宽度」语义——窄表撑满容器、宽表自动变宽由 wrap 滚动。此前各视图直接在 table 上写
// display:block + overflow-x，匿名 table 盒会收缩到内容宽，导致表格右侧留白铺不满。
md.renderer.rules.table_open = (tokens, idx, options, env, self) =>
  '<div class="md-table-wrap">' + self.renderToken(tokens, idx, options)
md.renderer.rules.table_close = (tokens, idx, options, env, self) =>
  self.renderToken(tokens, idx, options) + '</div>'

// 表格断行兜底：LLM 偶发把标题/序号文字与表头行粘在同一行（如「一、分布| 项目 | 数量 |」），
// 粘连行按表头解析出的列数与分隔行不符，GFM 判定非表格，整表回退纯文本。
// 检测「文字+竖线表头」且下一行是分隔行、断开后列数能对齐的行，在竖线前断开。
const rowCells = (row) => row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').length
const isDelimiterRow = (row) => /^[\s|:-]+$/.test(row) && row.includes('-')
const startsWithPipe = (row) => /^\s*\|/.test(row)

function breakGluedTable(text) {
  const lines = String(text ?? '').split('\n')
  for (let i = 0; i < lines.length - 1; i++) {
    const m = lines[i].match(/^([^|\n]*[^|\s])\s*(\|.*)$/)
    if (!m || !isDelimiterRow(lines[i + 1])) continue
    if (rowCells(m[2]) !== rowCells(lines[i + 1])) continue
    lines[i] = `${m[1]}\n${m[2]}`
  }
  return lines.join('\n')
}

// 表头物理断行兜底：列太多时 LLM 会把表头一行硬拆成两行输出（如断在「投资/完成备注」中间），
// GFM 按单行解析表头，断行后表头与分隔行列数对不上，整表回退纯文本。
// 仅当「行i 以竖线开头、行i+1 含竖线且非分隔行、行i+2 是分隔行」且两行拼接后列数与分隔行
// 一致时才合并（拼接保留行i 的收尾竖线、去掉行i+1 的开头竖线），列数强校验保证不误伤普通段落。
function mergeWrappedTableHeader(text) {
  const lines = String(text ?? '').split('\n')
  for (let i = 0; i < lines.length - 2; i++) {
    if (!startsWithPipe(lines[i]) || isDelimiterRow(lines[i])) continue
    if (!lines[i + 1].includes('|') || isDelimiterRow(lines[i + 1])) continue
    if (!isDelimiterRow(lines[i + 2])) continue
    const merged = `${lines[i].trimEnd()} ${lines[i + 1].trim().replace(/^\|/, '')}`
    if (rowCells(merged) !== rowCells(lines[i + 2])) continue
    lines[i] = merged
    lines.splice(i + 1, 1)
  }
  return lines.join('\n')
}

// 分隔行列数对齐兜底：GFM 严格要求分隔行列数与表头一致，列太多时 LLM 常偷懒少写
// （表头 46 列、分隔行只写 37 个 |-|），整表回退纯文本。把紧随表头的分隔行截断/补齐
// 到表头列数，已有单元格（含 :--- 等对齐标记）保留，缺的用 --- 补。
function alignDelimiterRow(text) {
  const lines = String(text ?? '').split('\n')
  for (let i = 0; i < lines.length - 1; i++) {
    if (!startsWithPipe(lines[i]) || isDelimiterRow(lines[i])) continue
    if (!isDelimiterRow(lines[i + 1])) continue
    const want = rowCells(lines[i])
    const cells = lines[i + 1].trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim())
    if (cells.length === want) continue
    const fixed = cells.slice(0, want).map(c => (c === '' ? '---' : c))
    while (fixed.length < want) fixed.push('---')
    lines[i + 1] = `| ${fixed.join(' | ')} |`
  }
  return lines.join('\n')
}

// 归一化顺序：先断开粘连标题，再合并断行表头（合并后分隔行才能紧随表头），最后对齐分隔行列数
const rawRender = md.render.bind(md)
md.render = (text) => rawRender(alignDelimiterRow(mergeWrappedTableHeader(breakGluedTable(text))))

export default md

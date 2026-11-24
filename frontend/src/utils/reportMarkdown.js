/**
 * 诊断报告专用 Markdown 渲染增强器：
 * 1. 基于共享 md 实例渲染基础 GFM（含表格断行/粘连/列数兜底）
 * 2. 行内代码按语义分色（规则证据 / 文件路径 / SQL与函数调用 / 字段标识符 / 默认代码）
 * 3. 智能识别诊断报告中中文引号「...」包裹的 SQL/字段名/路径并代码徽章化（普通中文不误伤）
 * 4. 代码块增加轻量语法高亮与语言角标头
 * 5. 安全 HTML 词法遍历（不破坏标签属性与 <pre>/<code> 内部），处理 R{n} 深链与规则证据徽章
 */
import md from '@/utils/markdown'

const RULE_TOKEN_RE = /\b((?:TOOL|REACT|SKILL|ANSWER)-\d{3}(?:@[A-Za-z0-9_/-]+)?)\b/g
const ROUND_TOKEN_RE = /(?<![A-Za-z0-9_])(R)(\d{1,3})(?![A-Za-z0-9_])/g
const CN_QUOTE_RE = /「([^」\n]{2,180})」/g

const FILE_EXT_RE = /\.(?:java|vue|js|ts|jsx|tsx|yml|yaml|json|sql|md|xml|py|css|scss|html|sh|properties|conf|log)$/i
const PATH_RE = /^(?:[a-zA-Z0-9_.-]+\/)+[a-zA-Z0-9_.-]+$/
const SQL_START_RE = /^(?:SELECT|WITH|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|EXPLAIN|SHOW)\b/i
const FUNC_CALL_RE = /[a-zA-Z_$][\w$.]*\s*\([^)]*\)/
const JSON_LIKE_RE = /^(?:\{[\s\S]*\}|\[[\s\S]*\])$/
const KV_EXPR_RE = /^[a-zA-Z_][\w.-]*\s*=\s*[^\s]+$/
const IDENT_RE = /^[a-zA-Z_][a-zA-Z0-9_.-]*$/

/** 反转义 HTML 实体（仅用于语义分类判断） */
function decodeHtmlEntities(str) {
  return String(str)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

/** 根据代码片段语义返回颜色分类 */
function classifyCodeKind(rawText) {
  const t = decodeHtmlEntities(rawText).trim()
  if (!t) return 'default'
  if (/^(?:TOOL|REACT|SKILL|ANSWER)-\d{3}(?:@[A-Za-z0-9_/-]+)?$/.test(t)) return 'rule'
  if (PATH_RE.test(t) || FILE_EXT_RE.test(t) || /^https?:\/\//i.test(t)) return 'path'
  if (SQL_START_RE.test(t) || JSON_LIKE_RE.test(t) || FUNC_CALL_RE.test(t) || KV_EXPR_RE.test(t)) return 'expr'
  if (IDENT_RE.test(t) && (t.includes('_') || t.includes('.') || /[a-z][A-Z]/.test(t) || /^[A-Z][A-Z0-9_]+$/.test(t))) {
    return 'ident'
  }
  return 'default'
}

/** 判断「...」内是否为代码/SQL/字段/路径，避免误伤普通中文词组 */
function isCodeInsideCnQuote(rawText) {
  const t = decodeHtmlEntities(rawText).trim()
  if (!t) return false
  // 含中文字符时，仅当明显是 SQL 语句或 JSON 对象才当代码
  if (/[\u4e00-\u9fa5]/.test(t)) {
    return SQL_START_RE.test(t) || (JSON_LIKE_RE.test(t) && t.includes(':'))
  }
  if (/^(?:TOOL|REACT|SKILL|ANSWER)-\d{3}/.test(t)) return true
  if (SQL_START_RE.test(t) || JSON_LIKE_RE.test(t) || FUNC_CALL_RE.test(t) || KV_EXPR_RE.test(t)) return true
  if (PATH_RE.test(t) || FILE_EXT_RE.test(t)) return true
  // 纯 ASCII 标识符（至少含下划线、点、横杠或驼峰，排除纯数字/纯短词）
  if (IDENT_RE.test(t) && t.length >= 3 && (t.includes('_') || t.includes('.') || t.includes('-') || /[a-z][A-Z]/.test(t))) {
    return true
  }
  return false
}

/** 代码块轻量语法高亮（输入为已转义的 HTML 文本） */
function highlightCodeBlock(escapedCode, lang = '') {
  const lowerLang = (lang || '').toLowerCase()
  const isSql = lowerLang === 'sql' || (!lowerLang && SQL_START_RE.test(decodeHtmlEntities(escapedCode).trim()))
  const isJson = lowerLang === 'json' || (!lowerLang && JSON_LIKE_RE.test(decodeHtmlEntities(escapedCode).trim()))

  const tokens = []
  const stash = (cls, text) => {
    const idx = tokens.length
    tokens.push(`<span class="hl-${cls}">${text}</span>`)
    return `\u0000HL${idx}\u0000`
  }

  let s = escapedCode

  // 1. 注释
  s = s.replace(/(\/\/[^\n]*|--[^\n]*|#[^\n]*)/g, (m) => stash('comment', m))
  s = s.replace(/(\/\*[\s\S]*?\*\/)/g, (m) => stash('comment', m))

  // 2. 字符串（含 JSON key 区分）
  if (isJson) {
    s = s.replace(/(&quot;(?:\\.|[^&]|&(?!quot;))*?&quot;)(\s*:)/g, (_, key, colon) => `${stash('key', key)}${colon}`)
  }
  s = s.replace(/(&quot;(?:\\.|[^&]|&(?!quot;))*?&quot;|&#39;(?:\\.|[^&]|&(?!#39;))*?&#39;)/g, (m) => stash('string', m))

  // 3. 关键字
  if (isSql) {
    s = s.replace(
      /\b(SELECT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|ON|GROUP|BY|ORDER|HAVING|LIMIT|OFFSET|AS|AND|OR|NOT|IN|IS|NULL|DISTINCT|COUNT|SUM|AVG|MIN|MAX|CASE|WHEN|THEN|ELSE|END|UNION|ALL|WITH|DESC|ASC|LIKE|BETWEEN|EXISTS)\b/gi,
      (m) => stash('keyword', m)
    )
  } else if (isJson) {
    s = s.replace(/\b(true|false|null)\b/g, (m) => stash('keyword', m))
  } else {
    s = s.replace(
      /\b(const|let|var|function|return|if|else|for|while|switch|case|break|import|export|from|default|class|new|try|catch|throw|async|await|public|private|protected|static|final|void|boolean|int|long|String|true|false|null|undefined|def|class|None|True|False)\b/g,
      (m) => stash('keyword', m)
    )
  }

  // 4. 函数/方法调用
  s = s.replace(/\b([a-zA-Z_][\w]*)(?=\s*\()/g, (m) => stash('fn', m))

  // 5. 数字
  s = s.replace(/\b(\d+(?:\.\d+)?)\b/g, (m) => stash('number', m))

  // 还原占位
  return s.replace(/\u0000HL(\d+)\u0000/g, (_, idx) => tokens[Number(idx)])
}

/** 规范化代码块：<pre><code class="language-xxx"> -> 带顶部语言栏的高亮容器 */
function enhanceCodeBlocks(html) {
  return html.replace(
    /<pre><code(?:\s+class="language-([^"]*)")?>([\s\S]*?)<\/code><\/pre>/g,
    (_, lang = '', codeHtml = '') => {
      const cleanLang = lang.trim()
      const decoded = decodeHtmlEntities(codeHtml).trim()
      let displayLang = cleanLang.toUpperCase()
      if (!displayLang) {
        if (SQL_START_RE.test(decoded)) displayLang = 'SQL'
        else if (JSON_LIKE_RE.test(decoded)) displayLang = 'JSON'
        else displayLang = 'CODE'
      }
      const highlighted = highlightCodeBlock(codeHtml, cleanLang || displayLang)
      return (
        `<div class="md-code-block">` +
        `<div class="md-code-header"><span class="md-code-lang">${displayLang}</span></div>` +
        `<pre><code>${highlighted}</code></pre>` +
        `</div>`
      )
    }
  )
}

/** 为行内 <code> 添加语义颜色类（跳过 <pre> 代码块内） */
function enhanceInlineCodes(html) {
  // 先临时保护 <pre>...</pre> 代码块，避免行内样式误作用到块级 code
  const blocks = []
  let safe = html.replace(/<pre>[\s\S]*?<\/pre>/g, (m) => {
    const idx = blocks.length
    blocks.push(m)
    return `\u0000CB${idx}\u0000`
  })

  safe = safe.replace(/<code>([\s\S]*?)<\/code>/g, (_, inner) => {
    const kind = classifyCodeKind(inner)
    return `<code class="md-code md-code-${kind}">${inner}</code>`
  })

  return safe.replace(/\u0000CB(\d+)\u0000/g, (_, idx) => blocks[Number(idx)])
}

/** HTML 标签感知的文本增强：只在普通文本节点中处理规则证据、代码引号、轮次深链 */
function enhanceTextNodes(html, { enableRoundLinks = false } = {}) {
  const parts = html.split(/(<[^>]+>)/g)
  let inPre = 0
  let inCode = 0
  let inAnchor = 0

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (!part) continue

    if (part.startsWith('<')) {
      const tag = part.toLowerCase()
      if (/^<pre[\s>]/.test(tag)) inPre++
      else if (/^<\/pre>/.test(tag)) inPre = Math.max(0, inPre - 1)
      else if (/^<code[\s>]/.test(tag)) inCode++
      else if (/^<\/code>/.test(tag)) inCode = Math.max(0, inCode - 1)
      else if (/^<a[\s>]/.test(tag)) inAnchor++
      else if (/^<\/a>/.test(tag)) inAnchor = Math.max(0, inAnchor - 1)
      continue
    }

    if (inPre > 0 || inCode > 0 || inAnchor > 0) continue

    // 使用占位保护本轮已生成的标签，避免后续正则二次命中内部文本
    const placeholders = []
    const hold = (snippet) => {
      const idx = placeholders.length
      placeholders.push(snippet)
      return `\u0000TX${idx}\u0000`
    }

    let text = part

    // 1. 规则/证据代号（TOOL-001@R16 / REACT-001 / SKILL-001 / ANSWER-002）
    text = text.replace(RULE_TOKEN_RE, (m) =>
      hold(`<code class="md-code md-code-rule">${m}</code>`)
    )

    // 2. 中文引号「...」里的 SQL/字段/路径 -> 语义代码胶囊
    text = text.replace(CN_QUOTE_RE, (full, inner) => {
      if (!isCodeInsideCnQuote(inner)) return full
      const kind = classifyCodeKind(inner)
      return hold(`<code class="md-code md-code-${kind}">${inner}</code>`)
    })

    // 3. 独立的 R{n} 轮次深链
    if (enableRoundLinks) {
      text = text.replace(ROUND_TOKEN_RE, (_, r, n) =>
        hold(`<span class="round-link" data-round="${n}" title="查看 R${n} 原文">${r}${n}</span>`)
      )
    }

    parts[i] = text.replace(/\u0000TX(\d+)\u0000/g, (_, idx) => placeholders[Number(idx)])
  }

  return parts.join('')
}

/**
 * 渲染诊断报告 Markdown
 * @param {string} markdownText 原始 Markdown
 * @param {{ stripHeaderMeta?: boolean, enableRoundLinks?: boolean }} options
 */
export function renderReportMarkdown(markdownText, options = {}) {
  let content = String(markdownText || '')
  if (!content) return ''

  if (options.stripHeaderMeta) {
    content = content
      .replace(/^#\s*单会话诊断报告\s*\n+/i, '')
      .replace(/^会话[:：]\s*[^\n]+\n+/i, '')
      .replace(/^触发来源[:：]\s*[^\n]+\n+/i, '')
      .replace(/^异常类型[:：]\s*[^\n]+\n+/i, '')
      .replace(/^诊断时间[:：]\s*[^\n]+\n+/i, '')
  }

  let html = md.render(content)
  html = enhanceCodeBlocks(html)
  html = enhanceInlineCodes(html)
  html = enhanceTextNodes(html, { enableRoundLinks: !!options.enableRoundLinks })
  return html
}

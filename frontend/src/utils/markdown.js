/**
 * 共享 markdown-it 实例（对话气泡 ChatView + 技能详情 SkillManageView 共用），
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

export default md

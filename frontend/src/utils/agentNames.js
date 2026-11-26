/**
 * 子Agent 编码与多Agent前端展示辅助（M-TT 监控链路跟踪 + todo-05 聊天页多Agent展示）：
 * 编码 → 中文名（对齐 SubAgentRegistry 四大垂类专员的角色定位）+ 状态/耗时/任务提取。
 * 未知编码原样返回（向后兼容新 agentCode，不白屏）。
 */

/** agentCode → 监控中心展示名（router=主链路） */
export function agentName(code) {
  return ({
    router: '主链路',
    'data-analysis': '问数子Agent',
    'declarant-fill': '填报子Agent',
    'reviewer-workflow': '审核子Agent',
    'declarant-workflow': '重提子Agent',
    'demo-sub': '演示子Agent'
  })[code] || code || ''
}

/** 子会话状态 → 中文文案 */
export function subStatusCn(status) {
  return {
    completed: '已完成',
    error: '失败',
    failed: '失败',
    timeout: '已超时',
    running: '运行中',
    interrupted: '已中断'
  }[status] || status || '-'
}

/** 子会话状态 → el-tag 类型 */
export function subTagType(status) {
  return {
    completed: 'success',
    error: 'danger',
    failed: 'danger',
    timeout: 'warning',
    running: 'warning',
    interrupted: 'info'
  }[status] || 'info'
}

/** 子会话状态 → 状态点符号（委派徽标行右侧） */
export function subStatusDot(status) {
  return {
    completed: '✅',
    error: '❌',
    failed: '❌',
    timeout: '⏱',
    running: '⏳',
    interrupted: '⏸'
  }[status] || '◌'
}

/** 委派工具名 → 专员展示元数据（用于 ChatView 多Agent卡片与右侧抽屉） */
export const SUB_AGENT_TOOL_META = {
  call_data_analysis: {
    code: 'data-analysis',
    label: '数据分析专员',
    tag: '问数分析',
    accent: 'blue'
  },
  call_declarant_fill: {
    code: 'declarant-fill',
    label: '资料填报专员',
    tag: '首次填报',
    accent: 'emerald'
  },
  call_reviewer_workflow: {
    code: 'reviewer-workflow',
    label: '审核审批专员',
    tag: '审核审批',
    accent: 'violet'
  },
  call_declarant_resubmit: {
    code: 'declarant-workflow',
    label: '驳回重提专员',
    tag: '驳回重提',
    accent: 'amber'
  },
  call_demo_sub: {
    code: 'demo-sub',
    label: '演示子专员',
    tag: '测试演示',
    accent: 'slate'
  }
}

const CODE_TO_LABEL = {
  'data-analysis': '数据分析专员',
  'declarant-fill': '资料填报专员',
  'reviewer-workflow': '审核审批专员',
  'declarant-workflow': '驳回重提专员',
  'demo-sub': '演示子专员'
}

/** 判断一个 timeline tool 节点是否为子 Agent 委派 */
export function isSubAgentTool(step) {
  if (!step || step.type !== 'tool') return false
  const key = (step.toolName || '').toLowerCase()
  if (SUB_AGENT_TOOL_META[key]) return true
  if (step.agentCode && step.agentCode !== 'router') return true
  if (Array.isArray(step.subSteps) && step.subSteps.length > 0) return true
  return false
}

/** 获取子 Agent 中文专员名称 */
export function subAgentDisplayName(step) {
  if (!step) return '子专员'
  const key = (step.toolName || '').toLowerCase()
  if (SUB_AGENT_TOOL_META[key]) return SUB_AGENT_TOOL_META[key].label
  if (step.agentCode && CODE_TO_LABEL[step.agentCode]) return CODE_TO_LABEL[step.agentCode]
  if (step.agentCode) return agentName(step.agentCode)
  return step.toolName || '子专员'
}

/** 获取子 Agent 业务标签 */
export function subAgentRoleTag(step) {
  const key = (step?.toolName || '').toLowerCase()
  return SUB_AGENT_TOOL_META[key]?.tag || '子专员'
}

/** 从委派工具参数 JSON 中提取 task 指令原文 */
export function extractSubTaskPrompt(argumentsStr) {
  if (!argumentsStr) return ''
  const raw = String(argumentsStr).trim()
  if (!raw) return ''
  try {
    const obj = JSON.parse(raw)
    if (obj && typeof obj === 'object' && typeof obj.task === 'string') {
      return obj.task.trim()
    }
    if (typeof obj === 'string') return obj.trim()
  } catch {
    /* 非 JSON 原样返回 */
  }
  return raw
}

/** 格式化子 Agent 耗时：不足 60s 显示 12.3s，超过 60s 显示 X 分 Y 秒 */
export function formatSubDuration(ms) {
  if (ms == null || Number.isNaN(Number(ms)) || ms < 0) return ''
  const totalSec = ms / 1000
  if (totalSec < 60) return `${totalSec.toFixed(1)}s`
  const rounded = Math.round(totalSec)
  const m = Math.floor(rounded / 60)
  const s = rounded % 60
  return `${m} 分 ${s} 秒`
}

/** 判断子工具是偏"查阅"还是"执行"（对齐参考图中的「已查阅 / 已执行」分类） */
const READ_TOOL_PREFIXES = ['list', 'get', 'describe', 'lookup', 'search', 'query', 'mypermissions', 'trace', 'analyze']

export function isReadLikeTool(toolName) {
  const k = (toolName || '').toLowerCase()
  return READ_TOOL_PREFIXES.some((p) => k.startsWith(p) || k.includes('read') || k.includes('view'))
}

/** 统计子 Agent 内部 subSteps 指标与最新动态 */
export function subStepStats(subSteps = []) {
  let readCount = 0
  let execCount = 0
  let thinkCount = 0
  let failCount = 0
  let runningTool = null
  let lastTool = null
  let lastText = ''
  let lastStep = null

  for (const s of subSteps) {
    lastStep = s
    if (s.type === 'thinking') {
      thinkCount++
    } else if (s.type === 'tool') {
      if (isReadLikeTool(s.toolName)) readCount++
      else execCount++
      if (s.status === 'failed') failCount++
      if (s.status === 'running') runningTool = s
      lastTool = s
    } else if (s.type === 'text' && s.content) {
      lastText = s.content
    }
  }

  return {
    readCount,
    execCount,
    totalTools: readCount + execCount,
    thinkCount,
    failCount,
    runningTool,
    lastTool,
    lastText,
    lastStep
  }
}

/**
 * 推导子 Agent 卡片的最终展示态与一句话摘要：
 * - state: 'running' | 'completed' | 'timeout' | 'failed' | 'interrupted'
 * - summary: 展示在卡片底部的简短结论/错误说明
 * - retryCount: 整体完成但内部存在失败工具（自愈重试）的次数
 */
export function detectSubAgentOutcome(step) {
  const stats = subStepStats(step?.subSteps || [])
  const rawResult = typeof step?.result === 'string' ? step.result.trim() : ''

  if (step?.status === 'running') {
    return { state: 'running', summary: '', retryCount: stats.failCount }
  }
  if (step?.status === 'interrupted') {
    return {
      state: 'interrupted',
      summary: '已随主任务停止（点击可查看中断前已执行步骤）',
      retryCount: 0
    }
  }

  // 解析结构化错误/超时/暂停 JSON（对齐 SubAgentTool 返回格式）
  if (rawResult.startsWith('{') && rawResult.endsWith('}')) {
    try {
      const parsed = JSON.parse(rawResult)
      if (parsed.timeout === true) {
        return {
          state: 'timeout',
          summary: parsed.error || '子专员执行超时，已自动终止',
          retryCount: 0
        }
      }
      if (parsed.status === 'PAUSED') {
        return {
          state: 'interrupted',
          summary: parsed.message || '子专员已随主任务暂停',
          retryCount: 0
        }
      }
      if (parsed.error) {
        return {
          state: 'failed',
          summary: String(parsed.error),
          retryCount: 0
        }
      }
    } catch {
      /* 非 JSON 按普通文本处理 */
    }
  }

  if (step?.status === 'failed') {
    return {
      state: 'failed',
      summary: rawResult ? rawResult.replace(/\s+/g, ' ').slice(0, 120) : '子专员执行异常',
      retryCount: 0
    }
  }

  // 正常完成：优先取最终返回结果首行，其次取 subSteps 里的最后一段 text
  const text = (rawResult || stats.lastText || '')
    .replace(/```json[\s\S]*?```/g, '')
    .replace(/[#>*`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  const summary = text ? (text.length > 96 ? `${text.slice(0, 96)}…` : text) : '已完成任务'
  return {
    state: 'completed',
    summary,
    retryCount: stats.failCount
  }
}

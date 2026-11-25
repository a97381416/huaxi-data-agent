/**
 * 子Agent 编码展示辅助（M-TT 监控链路跟踪子Agent穿透）：
 * 编码 → 中文名（对齐 SubAgentRegistry 四大垂类专员的角色定位）+ 子会话状态文案。
 * 未知编码原样返回（向后兼容新 agentCode，不白屏）。
 */

/** agentCode → 展示名（router=主链路） */
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
  return { completed: '已完成', error: '失败', running: '进行中', interrupted: '已中断' }[status] || status || '-'
}

/** 子会话状态 → el-tag 类型 */
export function subTagType(status) {
  return { completed: 'success', error: 'danger', running: 'warning', interrupted: 'info' }[status] || 'info'
}

/** 子会话状态 → 状态点符号（委派徽标行右侧） */
export function subStatusDot(status) {
  return { completed: '✅', error: '❌', running: '⏳', interrupted: '⏸' }[status] || '◌'
}

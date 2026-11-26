import request from '@/api/request'

/**
 * 一期 HITL 决策单与授权管理接口
 */

/** 审批决策单（批准 / 拒绝 / 本会话同类放行） */
export function decideHitlDecision(decisionId, data) {
  return request.post(`/api/hitl/decisions/${encodeURIComponent(decisionId)}/decide`, data)
}

/** 获取待我处理的决策单（全局汇总或按 conversationId 过滤） */
export function listPendingHitlDecisions(conversationId) {
  const params = { mine: true }
  if (conversationId) params.conversationId = conversationId
  return request.get('/api/hitl/decisions/pending', { params })
}

/** 获取指定会话的全部决策单（包含历史留痕与未决） */
export function listConversationHitlDecisions(conversationId) {
  return request.get('/api/hitl/decisions/conversation', {
    params: { conversationId }
  })
}

/** 一键撤销会话级同类放行授权 */
export function revokeHitlGrant(grantId) {
  return request.post(`/api/hitl/grants/${encodeURIComponent(grantId)}/revoke`)
}

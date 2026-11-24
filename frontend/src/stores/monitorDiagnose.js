import { defineStore } from 'pinia'

/**
 * AI 诊断抽屉全局状态（M5，D4 决策多入口）：
 * 列表页每行「诊断」（提问级）与详情页右上角「AI 诊断」（窗口级）、提问清单、
 * 风险卡「AI 诊断此风险」（规则级，06 调研）共用同一个抽屉实例（挂在监控页 MonitorShell）。
 * sessionId 非空 = 提问级诊断（后端聚焦该提问的问题/回答/轮次）；
 * ruleId 非空 = 规则级定向诊断（后端自动注入规则命中证据，rule 携带卡片展示字段）。
 */
export const useMonitorDiagnoseStore = defineStore('monitorDiagnose', {
  state: () => ({
    open: false,
    conversationId: '',
    sessionId: '',      // 非空 = 提问级诊断
    question: '',       // 用户补充的排查问题（选填）：空 = 默认全量体检
    ruleId: '',         // 非空 = 规则级定向诊断（须配合 sessionId）
    rule: null,         // 规则命中行快照（RiskItem：ruleId/title/severity/round/roundsHit/detail），抽屉上下文卡展示用
    model: ''           // 诊断模型 code：空 = 诊断默认（yml 专用诊断模型）；非空 = agentx_chat_model 的 code（清单同聊天侧）
  }),

  actions: {
    /**
     * @param conversationId 窗口 ID（必填，诊断上下文）
     * @param sessionId      提问 ID（选填；非空 = 提问级诊断）
     */
    openDiagnose(conversationId, sessionId = '') {
      this.conversationId = conversationId
      this.sessionId = sessionId || ''
      this.ruleId = ''
      this.rule = null
      this.open = true
    },

    /**
     * 规则级定向诊断（06 调研）：入口 = 风险卡「AI 诊断此风险」。
     * @param rule 风险命中行（RiskItem），抽屉只读上下文卡展示用
     */
    openRuleDiagnose(conversationId, sessionId, rule = null) {
      this.conversationId = conversationId
      this.sessionId = sessionId || ''
      this.ruleId = rule?.ruleId || ''
      this.rule = rule
      this.open = true
    },

    close() {
      this.open = false
    }
  }
})

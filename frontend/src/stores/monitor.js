import { defineStore } from 'pinia'

const FILTER_KEYS = [
  'userId',
  'sessionId',
  'conversationId',
  'summary',
  'status',
  'minMs',
  'maxMs',
  'start',
  'end',
  'q',
  'minRounds',
  'maxRounds',
  'riskOnly', // Step3 仅看有风险（'1' = true）
  'severity', // Step3 级别筛选：high/medium/low
  'ruleId', // Step3 规则筛选（join risk 表）
  'detectorVersion' // V2 检测引擎版本筛选：v1/v2
]

/**
 * 会话跟踪筛选状态（T3.2）：筛选与 URL 双向同步（设计 §6.2 ①：
 * 任意组合回车即查，全部同步进 URL，链接可直接分享）。
 * store 只管数据与序列化，路由跳转由视图组件执行（router 依赖不进 store）。
 */
export const useMonitorTraceStore = defineStore('monitorTrace', {
  state: () => ({
    filters: {
      userId: '',
      sessionId: '',
      conversationId: '',
      summary: '', // 会话摘要模糊搜索（后端 = 窗口 title LIKE）
      status: [], // 多选：running/completed/error/interrupted
      minMs: null,
      maxMs: null,
      start: '',
      end: '',
      q: '',
      minRounds: null,
      maxRounds: null,
      riskOnly: '', // Step3 仅看有风险（'1' = true）
      severity: '', // Step3 级别筛选：high/medium/low
      ruleId: '', // Step3 规则筛选
      detectorVersion: '' // V2 检测引擎版本：v1/v2
    },
    page: 0,
    size: 20
  }),

  actions: {
    /** 从路由 query 恢复筛选（进页面/分享链接打开时） */
    syncFromQuery(query = {}) {
      for (const key of FILTER_KEYS) {
        const v = query[key]
        if (v === undefined || v === null || v === '') {
          if (key === 'status') this.filters.status = []
          else if (key === 'minMs' || key === 'maxMs' || key === 'minRounds' || key === 'maxRounds') this.filters[key] = null
          else this.filters[key] = ''
        } else if (key === 'status') {
          this.filters.status = String(v).split(',').filter(Boolean)
        } else if (key === 'minMs' || key === 'maxMs' || key === 'minRounds' || key === 'maxRounds') {
          const n = Number(v)
          this.filters[key] = Number.isFinite(n) ? n : null
        } else {
          this.filters[key] = String(v)
        }
      }
      this.page = Math.max(0, Number(query.page ?? 0) || 0)
      this.size = Math.max(1, Number(query.size ?? 20) || 20)
    },

    /** 筛选 → URL query（空值不写入，保持链接干净） */
    toQuery() {
      const q = {}
      for (const key of FILTER_KEYS) {
        const v = this.filters[key]
        if (key === 'status') {
          if (v && v.length) q.status = v.join(',')
        } else if (key === 'minMs' || key === 'maxMs' || key === 'minRounds' || key === 'maxRounds') {
          if (v !== null && v !== undefined && v !== '') q[key] = String(v)
        } else if (v) {
          q[key] = String(v)
        }
      }
      if (this.page > 0) q.page = String(this.page)
      if (this.size !== 20) q.size = String(this.size)
      return q
    },

    /** 请求参数（剔除空值） */
    requestParams() {
      const params = { page: this.page, size: this.size }
      for (const key of FILTER_KEYS) {
        const v = this.filters[key]
        if (key === 'status') {
          if (v && v.length) params.status = v.join(',')
        } else if (key === 'minMs' || key === 'maxMs' || key === 'minRounds' || key === 'maxRounds') {
          if (v !== null && v !== undefined && v !== '') params[key] = v
        } else if (v) {
          params[key] = v
        }
      }
      return params
    },

    reset() {
      this.filters = {
        userId: '',
        sessionId: '',
        conversationId: '',
        summary: '',
        status: [],
        minMs: null,
        maxMs: null,
        start: '',
        end: '',
        q: '',
        minRounds: null,
        maxRounds: null,
        riskOnly: '',
        severity: '',
        ruleId: '',
        detectorVersion: ''
      }
      this.page = 0
    }
  }
})

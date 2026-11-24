<template>
  <div class="flex h-full gap-3 p-4">
    <!-- 列表栏（支持折叠/展开，优化默认宽度比例） -->
    <section
      class="flex shrink-0 flex-col overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm transition-all duration-300"
      :class="isLeftCollapsed ? 'w-12 items-center' : 'w-[36%] min-w-[340px] max-w-[480px]'"
    >
      <!-- 折叠状态下的侧边条 -->
      <div v-if="isLeftCollapsed" class="flex h-full flex-col items-center py-4">
        <el-tooltip content="展开报告列表" placement="right">
          <el-button text circle size="small" @click="isLeftCollapsed = false">
            <el-icon><Expand /></el-icon>
          </el-button>
        </el-tooltip>
        <div class="mt-8 select-none text-xs font-medium tracking-widest text-gray-400 [writing-mode:vertical-lr]">
          报告中心 ({{ total }})
        </div>
      </div>

      <!-- 展开状态下的完整列表 -->
      <template v-else>
        <div class="flex shrink-0 items-center justify-between border-b border-gray-50 px-3 py-2.5">
          <div class="flex items-center gap-2">
            <span class="text-sm font-semibold text-gray-700">报告中心</span>
            <el-tag size="small" type="info" round class="scale-90 font-mono">{{ total }}</el-tag>
          </div>
          <div class="flex items-center gap-2">
            <el-radio-group v-model="typeFilter" size="small" @change="search(0)">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="single">诊断</el-radio-button>
              <el-radio-button value="daily">日报</el-radio-button>
            </el-radio-group>
            <el-tooltip content="收起列表" placement="top">
              <el-button text circle size="small" @click="isLeftCollapsed = true">
                <el-icon><Fold /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>

        <div class="min-h-0 flex-1 overflow-auto">
          <el-table
            v-loading="loading"
            :data="rows"
            size="small"
            highlight-current-row
            :row-class-name="rowClass"
            @row-click="openDetail"
          >
            <el-table-column label="类型" width="68" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type === 'daily' ? 'warning' : 'primary'" effect="plain">
                  {{ row.type === 'daily' ? '日报' : '诊断' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="范围/会话" min-width="110">
              <template #default="{ row }">
                <span v-if="row.type === 'daily'" class="font-mono text-xs text-gray-600">
                  {{ (row.periodStart || '').slice(0, 10) }}
                </span>
                <span
                  v-else
                  class="cursor-pointer font-mono text-xs text-blue-600 hover:underline"
                  :title="row.conversationId"
                  @click.stop="goConversation(row.conversationId)"
                >
                  {{ shortId(row.conversationId) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="65" align="center">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'done' ? 'success' : row.status === 'failed' ? 'danger' : 'info'"
                  disable-transitions
                >
                  {{ row.status === 'done' ? '完成' : row.status === 'failed' ? '失败' : row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="170">
              <template #default="{ row }">
                <div class="line-clamp-2 text-xs leading-5">
                  <template v-if="getSummaryBadges(row.summaryMd)">
                    <div class="flex flex-wrap items-center gap-1">
                      <span class="rounded bg-blue-50 px-1 py-0.2 font-medium text-blue-700">
                        {{ getSummaryBadges(row.summaryMd).attribution }}
                      </span>
                      <span
                        v-if="getSummaryBadges(row.summaryMd).confidence"
                        class="rounded px-1 py-0.2 text-[10px]"
                        :class="confidenceClass(getSummaryBadges(row.summaryMd).confidence)"
                      >
                        {{ getSummaryBadges(row.summaryMd).confidence }}
                      </span>
                      <span
                        v-if="getSummaryBadges(row.summaryMd).round"
                        class="rounded bg-amber-50 px-1 py-0.2 font-mono text-[11px] text-amber-700"
                      >
                        {{ getSummaryBadges(row.summaryMd).round }}
                      </span>
                    </div>
                  </template>
                  <span v-else class="text-gray-500">{{ cleanSummaryText(row.summaryMd) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="时间" prop="createdAt" width="125">
              <template #default="{ row }">
                <span class="font-mono text-[11px] text-gray-400" :title="row.createdAt">
                  {{ (row.createdAt || '').slice(5, 16) }}
                </span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无报告记录" :image-size="60" />
            </template>
          </el-table>
        </div>

        <div class="flex shrink-0 items-center justify-between border-t border-gray-50 px-3 py-2">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="size"
            :total="total"
            layout="prev, pager, next"
            small
            background
            @current-change="load"
          />
        </div>
      </template>
    </section>

    <!-- 详情栏（诊断上下文与提问横幅 + 结构化元数据 + 优雅排版 Markdown） -->
    <section class="flex min-w-0 flex-1 flex-col overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm">
      <template v-if="detail">
        <!-- 详情顶部操作栏 -->
        <div class="flex shrink-0 items-center justify-between border-b border-gray-50 px-5 py-2.5">
          <div class="flex items-center gap-2">
            <el-tag size="small" :type="detail.type === 'daily' ? 'warning' : 'primary'" effect="light">
              {{ detail.type === 'daily' ? '📅 监控日报' : '🔍 会话诊断报告' }}
            </el-tag>
            <span class="text-sm font-semibold text-gray-800">
              {{ detail.type === 'daily' ? `周期: ${(detail.periodStart || '').slice(0, 10)}` : '单会话详细诊断' }}
            </span>
            <span class="text-xs text-gray-400">· 生成于 {{ detail.createdAt }}</span>
          </div>
          <div class="flex items-center gap-1.5">
            <el-button text size="small" @click="exportMd">
              <el-icon class="mr-1"><Download /></el-icon>导出 Markdown
            </el-button>
            <el-button text size="small" @click="copyLink">
              <el-icon class="mr-1"><Link /></el-icon>分享链接
            </el-button>
          </div>
        </div>

        <!-- 诊断上下文与提问横幅（单会话诊断专用） -->
        <div v-if="detail.type === 'single'" class="shrink-0 border-b border-gray-100 bg-slate-50/70 p-4 space-y-3">
          <!-- 1. 用户提问展示区 -->
          <div class="flex items-start gap-2.5 rounded-lg border border-blue-100/80 bg-blue-50/50 p-2.5 text-xs text-slate-700">
            <span class="mt-0.5 inline-flex shrink-0 items-center gap-1 font-semibold text-blue-700">
              <el-icon><ChatDotSquare /></el-icon>用户提问:
            </span>
            <div class="flex-1 font-medium text-slate-800 leading-relaxed select-text">
              <template v-if="displayQuestion">
                “{{ displayQuestion }}”
              </template>
              <span v-else class="text-gray-400 italic">（未记录问题原文，来自全会话综合诊断）</span>
            </div>
            <el-tooltip v-if="displayQuestion" content="复制问题内容" placement="top">
              <el-button text circle size="small" class="!p-0.5 shrink-0" @click="copyText(displayQuestion, '用户提问')">
                <el-icon :size="12"><DocumentCopy /></el-icon>
              </el-button>
            </el-tooltip>
          </div>

          <!-- 2. 诊断模式与整体检测/规则异常状态 -->
          <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-gray-100 bg-white p-2.5 shadow-2xs">
            <!-- 左侧：模式识别与规则/整体范围 -->
            <div class="flex flex-wrap items-center gap-2">
              <!-- 场景 A：针对规则异常 -->
              <template v-if="isRuleDiagnose">
                <el-tag type="danger" effect="dark" size="small" class="font-semibold">
                  ⚠️ 针对规则异常
                </el-tag>
                <div class="flex items-center gap-1.5 text-xs">
                  <span class="text-gray-400">异常规则:</span>
                  <span class="font-mono font-semibold text-rose-700 bg-rose-50 px-1.5 py-0.5 rounded border border-rose-200/60">
                    [{{ displayRule.id }}]
                  </span>
                  <span class="font-medium text-slate-800">{{ displayRule.title }}</span>
                  <el-tag v-if="displayRule.severity" size="small" type="danger" effect="plain" class="scale-90">
                    {{ displayRule.severity }}
                  </el-tag>
                  <span
                    v-if="displayRule.round"
                    class="round-link ml-1 cursor-pointer font-mono font-semibold text-amber-700 bg-amber-50 px-1.5 py-0.5 rounded hover:bg-amber-100 text-[11px]"
                    :data-round="displayRule.round"
                    title="点击定位到异常轮次"
                  >
                    触发于 R{{ displayRule.round }}
                  </span>
                </div>
              </template>

              <!-- 场景 B：单问题整体诊断 -->
              <template v-else>
                <el-tag type="primary" effect="dark" size="small" class="font-semibold">
                  🌐 单问题整体诊断
                </el-tag>
                <span class="text-xs text-slate-600 bg-slate-50 px-2 py-0.5 rounded border border-slate-200/60">
                  全链路检测: 意图理解 ➔ REACT推理 ➔ 工具执行 ➔ 结果生成
                </span>
                <el-tag v-if="parsedMetrics?.anomalyType" size="small" type="warning" effect="light">
                  {{ parsedMetrics.anomalyType }}
                </el-tag>
              </template>
            </div>

            <!-- 右侧：归因结论与置信度 -->
            <div class="flex items-center gap-2 text-xs">
              <span class="text-gray-400">归因结论:</span>
              <el-tag size="small" type="primary" effect="plain" class="font-medium">
                {{ parsedMetrics?.attribution || '已完成排查' }}
              </el-tag>
              <el-tag
                v-if="parsedMetrics?.confidence"
                size="small"
                :class="confidenceClass(parsedMetrics.confidence)"
              >
                置信度: {{ parsedMetrics.confidence }}
              </el-tag>
              <template v-if="parsedMetrics?.affectedRound && parsedMetrics.affectedRound !== '0'">
                <span
                  class="round-link cursor-pointer rounded bg-amber-50 px-1.5 py-0.5 font-mono font-semibold text-amber-700 hover:bg-amber-100 text-[11px]"
                  :data-round="parsedMetrics.affectedRound"
                  title="点击查看影响轮次"
                >
                  影响轮: R{{ parsedMetrics.affectedRound }}
                </span>
              </template>
            </div>
          </div>

          <!-- 3. 会话与执行链路链接 -->
          <div class="flex items-center justify-between text-[11px] text-gray-500 pt-0.5 px-0.5">
            <div class="flex items-center gap-3">
              <div class="flex items-center gap-1 font-mono">
                <span class="text-gray-400">会话编号:</span>
                <span
                  class="cursor-pointer text-blue-600 hover:underline"
                  :title="'会话 ID: ' + detail.conversationId"
                  @click="goConversation(detail.conversationId)"
                >
                  {{ detail.conversationId }}
                </span>
                <el-tooltip content="复制会话 ID" placement="top">
                  <el-button text circle size="small" class="!p-0.5" @click="copyText(detail.conversationId, '会话 ID')">
                    <el-icon :size="11"><DocumentCopy /></el-icon>
                  </el-button>
                </el-tooltip>
              </div>

              <div v-if="displaySessionId" class="flex items-center gap-1 font-mono">
                <span class="text-gray-400">· 提问编号:</span>
                <span class="text-slate-600">{{ displaySessionId }}</span>
              </div>
            </div>

            <el-button
              type="primary"
              link
              size="small"
              class="!text-xs"
              @click="goTraceTimeline"
            >
              查看完整会话链路 ➔
            </el-button>
          </div>
        </div>

        <!-- 报告 Markdown 内容区 -->
        <div
          ref="detailRef"
          class="report-md min-h-0 flex-1 overflow-auto px-6 py-4"
          @click="onDetailClick"
          v-html="renderedDetail"
        />
      </template>

      <!-- 空白占位 -->
      <div v-else class="flex flex-1 flex-col items-center justify-center text-gray-400">
        <el-empty description="点击左侧报告查看详细分析" :image-size="90" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Link, Fold, Expand, DocumentCopy, ChatDotSquare } from '@element-plus/icons-vue'
import { fetchReports, fetchReportDetail } from '@/api/monitor'
import { renderReportMarkdown } from '@/utils/reportMarkdown'
import '@/assets/report-markdown.css'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const typeFilter = ref('')
const detail = ref(null)
const detailRef = ref(null)
const isLeftCollapsed = ref(false)

const currentPage = computed({
  get: () => page.value + 1,
  set: (v) => (page.value = Math.max(0, v - 1))
})

/** 解析 detail.metricsJson */
const parsedMetrics = computed(() => {
  if (!detail.value?.metricsJson) return null
  try {
    return typeof detail.value.metricsJson === 'string'
      ? JSON.parse(detail.value.metricsJson)
      : detail.value.metricsJson
  } catch {
    return null
  }
})

/** 提取用户提问（三级兼容：metrics.question -> 正文正则解析 -> 空） */
const displayQuestion = computed(() => {
  if (parsedMetrics.value?.question) {
    return parsedMetrics.value.question
  }
  const rawMd = detail.value?.detailMd || ''
  // 匹配形如：触发来源: 提问级全面诊断，用户反馈（xxx） 或 用户反馈（xxx）
  const feedbackMatch = rawMd.match(/用户反馈[（\(](.*?)[）\)]/)
  if (feedbackMatch && feedbackMatch[1]?.trim()) {
    return feedbackMatch[1].trim()
  }
  // 匹配形如：问题原文: xxx 或 提问: xxx
  const questionMatch = rawMd.match(/(?:问题原文|提问|用户问题)[:：]\s*(.*?)(?:\n|$)/)
  if (questionMatch && questionMatch[1]?.trim()) {
    return questionMatch[1].trim()
  }
  return ''
})

/** 提取提问编号 sessionId */
const displaySessionId = computed(() => {
  if (parsedMetrics.value?.sessionId) {
    return parsedMetrics.value.sessionId
  }
  const rawMd = detail.value?.detailMd || ''
  const m = rawMd.match(/(?:sessionId|会话[：:])\s*([a-zA-Z0-9_-]+)/)
  return m ? m[1] : ''
})

/** 判断是否为针对规则异常诊断 */
const isRuleDiagnose = computed(() => {
  if (parsedMetrics.value?.diagnoseScope === 'rule') return true
  if (parsedMetrics.value?.ruleId) return true
  const rawMd = detail.value?.detailMd || ''
  return rawMd.includes('单规则定向诊断') || rawMd.includes('规则级定向诊断')
})

/** 提取异常规则信息 */
const displayRule = computed(() => {
  const m = parsedMetrics.value || {}
  let ruleId = m.ruleId || ''
  let ruleTitle = m.ruleTitle || ''
  let severity = m.ruleSeverity || ''
  let round = m.ruleRound || ''

  if (!ruleId) {
    const rawMd = detail.value?.detailMd || ''
    const idMatch = rawMd.match(/规则\s*([A-Z0-9_]+)/)
    if (idMatch) ruleId = idMatch[1]
  }

  // 常见规则 ID 别名对照
  const RULE_TITLES = {
    'TOOL_CALL_FAILED': '工具调用失败/异常',
    'REACT_LOOP': 'ReAct 思考死循环风险',
    'EMPTY_ANSWER': '模型空回答',
    'PARAM_INVALID': '入参校验未通过',
    'TIMEOUT': '单轮执行超时'
  }

  return {
    id: ruleId || 'RULE_DETECT',
    title: ruleTitle || RULE_TITLES[ruleId] || '规则检测异常项',
    severity: severity || '高危',
    round: round || m.affectedRound || ''
  }
})

/** 优雅排版渲染 Markdown：语义分色代码胶囊 + 高辨识度表格 + R{n} 轮次深链 + 规则证据徽章 */
const renderedDetail = computed(() =>
  renderReportMarkdown(detail.value?.detailMd || '', {
    stripHeaderMeta: true,
    enableRoundLinks: !!detail.value?.conversationId
  })
)

async function load() {
  loading.value = true
  try {
    const data = await fetchReports({ type: typeFilter.value || undefined, page: page.value, size })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

function search(p = 0) {
  page.value = p
  router.replace({ query: { ...route.query, type: typeFilter.value || undefined, id: undefined } })
  load()
}

async function openDetail(row) {
  try {
    detail.value = await fetchReportDetail(row.id)
    router.replace({ query: { ...route.query, id: String(row.id) } })
  } catch {
    /* 拦截器已弹错 */
  }
}

/** 证据深链：点击正文里的 R{n} → 跳转至会话详情定位该轮 */
function onDetailClick(e) {
  const target = e.target.closest('.round-link')
  if (!target) return
  const convId = detail.value?.conversationId
  if (!convId) return
  const round = target.dataset.round
  if (!round || round === '0') return
  router.push({
    path: `/monitor/traces/${encodeURIComponent(convId)}`,
    query: { round: String(round) }
  })
}

function goConversation(convId) {
  if (!convId) return
  router.push({ path: `/monitor/traces/${encodeURIComponent(convId)}` })
}

function goTraceTimeline() {
  const convId = detail.value?.conversationId
  if (!convId) return
  const query = {}
  if (displaySessionId.value) query.sessionId = displaySessionId.value
  if (displayRule.value?.round) query.round = String(displayRule.value.round)
  router.push({
    path: `/monitor/traces/${encodeURIComponent(convId)}`,
    query
  })
}

function rowClass({ row }) {
  return String(route.query.id) === String(row.id) ? 'row-current' : ''
}

function exportMd() {
  if (!detail.value) return
  const blob = new Blob([detail.value.detailMd || ''], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  const name = detail.value.type === 'daily'
    ? `日报_${(detail.value.periodStart || '').slice(0, 10)}`
    : `诊断_${shortId(detail.value.conversationId)}`
  a.download = `${name}.md`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出 Markdown 报告')
}

async function copyLink() {
  const url = new URL(window.location.href)
  if (detail.value) url.searchParams.set('id', String(detail.value.id))
  try {
    await navigator.clipboard.writeText(url.toString())
    ElMessage.success('报告链接已复制至剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function copyText(text, label = '内容') {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.warning('复制失败')
  }
}

/** 结构化提取摘要中的归因标签 */
function getSummaryBadges(text) {
  if (!text) return null
  const attrMatch = text.match(/归因:\s*([^(\[\s|]+)/)
  if (!attrMatch) return null

  const confMatch = text.match(/置信度:\s*([^)\s|\]]+)/)
  const roundMatch = text.match(/影响轮:\s*(R?\d+)/)

  return {
    attribution: attrMatch[1].trim(),
    confidence: confMatch ? confMatch[1].trim() : '',
    round: roundMatch ? (roundMatch[1].startsWith('R') ? roundMatch[1] : `R${roundMatch[1]}`) : ''
  }
}

/** 清洗旧数据摘要文本中的 CoT 思考过程及多余 Markdown 前缀 */
function cleanSummaryText(text) {
  if (!text) return '-'
  let cleaned = text
    .replace(/^#+.*$/gm, '')
    .replace(/(?:I'll start by|Let me analyze|reading the trace|reading the conversa).*$/gis, '')
    .replace(/\s+/g, ' ')
    .trim()
  return cleaned.length > 50 ? cleaned.slice(0, 50) + '…' : cleaned || '-'
}

function confidenceClass(conf) {
  if (!conf) return 'bg-gray-100 text-gray-600'
  if (conf.includes('高')) return 'bg-emerald-50 text-emerald-700'
  if (conf.includes('中')) return 'bg-amber-50 text-amber-700'
  return 'bg-blue-50 text-blue-600'
}

function shortId(id) {
  return id && id.length > 16 ? id.slice(0, 10) + '…' + id.slice(-4) : id || '-'
}

/** URL 深链恢复 */
watch(
  () => route.query,
  (q) => {
    if (route.name !== 'monitor-reports') return
    if (q.type !== undefined) typeFilter.value = String(q.type || '')
    if (q.id && (!detail.value || String(detail.value.id) !== String(q.id))) {
      fetchReportDetail(String(q.id)).then((d) => {
        detail.value = d
      }).catch(() => {})
    }
  },
  { immediate: true }
)

onMounted(load)
</script>

<style>
/* 表格当前选中行微高亮 */
.el-table .row-current {
  background-color: #f0f7ff !important;
}
</style>
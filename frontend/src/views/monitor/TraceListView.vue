<template>
  <div class="flex h-full flex-col p-4">
    <!-- 页头 -->
    <div class="mb-3 flex items-center justify-between">
      <div>
        <h1 class="m-0 text-[17px] font-semibold text-gray-900">提问跟踪</h1>
        <p class="mb-0 mt-0.5 text-xs text-gray-400">一行 = 一次提问：问题原文 / 最终回答 / 轮次与耗时行内直出；问题 / 会话摘要模糊搜索单表 LIKE（数据源：监控自有表）</p>
      </div>
      <div class="flex items-center gap-2">
        <el-button size="small" :disabled="!selectedTerminal.length" type="warning" @click="onBatchRerun">
          批量重诊断{{ selectedTerminal.length ? `（${selectedTerminal.length}）` : '' }}
        </el-button>
        <el-button size="small" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>

    <!-- 筛选区 -->
    <div class="rounded-xl border border-gray-100 bg-white p-3.5 shadow-sm">
      <div class="flex flex-wrap items-center gap-x-4 gap-y-2.5">
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          user_id
          <el-input
            v-model="store.filters.userId"
            clearable
            placeholder="精确匹配"
            class="!w-[150px]"
            @keyup.enter="search(0)"
            @clear="search(0)"
          />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          session_id
          <el-input
            v-model="store.filters.sessionId"
            clearable
            placeholder="精确匹配"
            class="!w-[190px]"
            @keyup.enter="search(0)"
            @clear="search(0)"
          />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          conversation_id
          <el-input
            v-model="store.filters.conversationId"
            clearable
            placeholder="精确匹配"
            class="!w-[210px]"
            @keyup.enter="search(0)"
            @clear="search(0)"
          />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          会话摘要
          <el-input
            v-model="store.filters.summary"
            clearable
            placeholder="搜会话摘要（窗口首问），模糊匹配"
            class="!w-[200px]"
            @keyup.enter="search(0)"
            @clear="search(0)"
          />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          问题模糊
          <el-input
            v-model="store.filters.q"
            clearable
            placeholder="搜问题原文，追问也能直接搜到"
            class="!w-[220px]"
            @keyup.enter="search(0)"
            @clear="search(0)"
          />
        </label>
        <el-button type="primary" size="small" @click="search(0)">查询</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>
      <div class="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-2.5">
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          状态
          <el-select v-model="store.filters.status" multiple collapse-tags clearable placeholder="全部"
                     class="!w-[220px]" @change="search(0)">
            <el-option label="进行中" value="running" />
            <el-option label="完成" value="completed" />
            <el-option label="失败" value="error" />
            <el-option label="已中断" value="interrupted" />
          </el-select>
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          时间
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD HH:mm:ss"
            class="!w-[340px]"
            @change="onTimeRangeChange"
          />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          耗时 (s)
          <el-input-number v-model="minSeconds" :min="0" :controls="false" placeholder="最小"
                           class="!w-[80px]" @change="onSecondsChange" />
          <span class="text-gray-300">~</span>
          <el-input-number v-model="maxSeconds" :min="0" :controls="false" placeholder="最大"
                           class="!w-[80px]" @change="onSecondsChange" />
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          轮数
          <el-input-number v-model="minRounds" :min="0" :controls="false" placeholder="最小"
                           class="!w-[80px]" @change="onRoundsChange" />
          <span class="text-gray-300">~</span>
          <el-input-number v-model="maxRounds" :min="0" :controls="false" placeholder="最大"
                           class="!w-[80px]" @change="onRoundsChange" />
        </label>
        <el-checkbox v-model="riskOnlyChecked" size="small" @change="onRiskFilterChange">仅看有风险</el-checkbox>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          级别
          <el-select v-model="store.filters.severity" clearable placeholder="全部" class="!w-[130px]"
                     @change="onRiskFilterChange">
            <el-option label="❗异常" value="high" />
            <el-option label="⚠ 待人工确认" value="medium" />
            <el-option label="风险提示" value="low" />
          </el-select>
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          规则
          <el-select v-model="store.filters.ruleId" clearable filterable placeholder="全部" class="!w-[220px]"
                     @change="onRiskFilterChange">
            <el-option v-for="r in ruleOptions" :key="r.ruleId"
                       :label="`${r.ruleId} ${r.title}`" :value="r.ruleId" />
          </el-select>
        </label>
        <label class="flex items-center gap-1.5 text-xs text-gray-500">
          检测版本
          <el-select v-model="store.filters.detectorVersion" clearable placeholder="全部" class="!w-[100px]"
                     @change="search(0)">
            <el-option label="v2" value="v2" />
            <el-option label="v1（历史）" value="v1" />
          </el-select>
        </label>
      </div>
    </div>

    <!-- 表格 -->
    <div class="mt-3 min-h-0 flex-1 overflow-auto rounded-xl border border-gray-100 bg-white shadow-sm">
      <el-table
        v-loading="loading"
        :data="rows"
        size="default"
        :row-class-name="rowClass"
        height="100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" :selectable="isTerminalRow" />
        <el-table-column label="conversation_id" width="150">
          <template #default="{ row }">
            <span class="cursor-pointer font-mono text-xs text-gray-400 hover:text-blue-600"
                  title="查看该窗口的提问详情（默认聚焦第一问）" @click="goConversation(row)">
              {{ shortId(row.conversationId) }}
            </span>
            <el-icon class="ml-1 cursor-pointer align-middle text-gray-300 hover:text-blue-500"
                     @click="copyText(row.conversationId, 'conversation_id')">
              <CopyDocument />
            </el-icon>
            <span v-if="row.seq" class="ml-1 rounded bg-blue-50 px-1 text-[10px] text-blue-500">Q{{ row.seq }}</span>
          </template>
        </el-table-column>
        <el-table-column label="会话摘要" min-width="160">
          <template #default="{ row }">
            <span v-if="row.conversationTitle"
                  class="line-clamp-2 cursor-pointer whitespace-pre-wrap break-words text-xs text-gray-500 hover:text-blue-600"
                  :title="row.conversationTitle" @click="goConversation(row)">
              {{ row.conversationTitle }}
            </span>
            <span v-else class="text-xs text-gray-300">-</span>
          </template>
        </el-table-column>
        <el-table-column label="user_id" prop="userId" width="100" show-overflow-tooltip />
        <el-table-column label="session_id" width="160">
          <template #default="{ row }">
            <span class="font-mono text-xs" :title="row.sessionId">{{ shortId(row.sessionId) }}</span>
            <el-icon class="ml-1 cursor-pointer align-middle text-gray-300 hover:text-blue-500"
                     @click="copyText(row.sessionId, 'session_id')">
              <CopyDocument />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="问题" min-width="230">
          <template #default="{ row }">
            <span class="line-clamp-2 cursor-pointer whitespace-pre-wrap break-words text-gray-700 hover:text-blue-600"
                  :title="row.question" @click="goDetail(row)">
              {{ row.question || '（无内容）' }}
            </span>
            <div v-if="row.firstError" class="mt-0.5 truncate text-xs text-red-500" :title="row.firstError">
              错误: {{ row.firstError }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最终回答" min-width="230">
          <template #default="{ row }">
            <template v-if="row.answerPreview">
              <span class="line-clamp-2 cursor-pointer whitespace-pre-wrap break-words text-xs text-gray-500 hover:text-blue-600"
                    title="点击弹窗查看回答全文（MD 渲染）" @click="openAnswer(row)">
                {{ row.answerPreview }}
              </span>
              <span v-if="row.answerChars" class="ml-1 shrink-0 text-[10px] text-gray-300">{{ row.answerChars }}字</span>
            </template>
            <span v-else-if="row.status === 'running'" class="text-xs text-gray-300">回答生成中…</span>
            <span v-else class="text-xs text-gray-300">-</span>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="row.firstRisk" :content="row.firstRisk" placement="top" :show-after="200">
              <span class="cursor-default">
                <span v-if="(row.riskHigh || 0) > 0" class="font-semibold text-red-600">❗{{ row.riskHigh }}</span>
                <span v-if="(row.riskTotal || 0) - (row.riskHigh || 0) > 0" class="ml-0.5 font-semibold text-orange-500">
                  ⚠ {{ row.riskTotal - row.riskHigh }}
                </span>
              </span>
            </el-tooltip>
            <span v-else-if="row.detectStatus === 'done'" class="text-gray-300">—</span>
            <span v-else-if="row.status !== 'running'" class="text-[11px] text-gray-300">未检测</span>
            <span v-else class="text-[11px] text-gray-300">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)" disable-transitions>{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检测" width="96">
          <template #default="{ row }">
            <span v-if="row.detectorVersion"
                  class="rounded px-1 text-[11px]"
                  :class="row.detectorVersion === 'v2' ? 'bg-blue-50 text-blue-500' : 'bg-gray-100 text-gray-400'">
              {{ row.detectorVersion }}
            </span>
            <span v-else class="text-[11px] text-gray-300">—</span>
          </template>
        </el-table-column>
        <el-table-column label="轮次" width="86">
          <template #default="{ row }">
            <span>{{ row.rounds ?? '-' }}</span>
            <span v-if="(row.failRounds || 0) > 0" class="ml-1 text-xs text-red-500">失败{{ row.failRounds }}</span>
          </template>
        </el-table-column>
        <el-table-column label="tokens" width="100">
          <template #default="{ row }">{{ formatTokens((row.promptTokens || 0) + (row.completionTokens || 0)) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="86">
          <template #default="{ row }">{{ formatDuration(rowDurationMs(row)) }}</template>
        </el-table-column>
        <el-table-column label="提问时间" prop="createdAt" width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goDetail(row)">详情</el-button>
            <!-- 提问级 AI 诊断：聚焦该提问的问题/回答/轮次 -->
            <el-button link type="warning" size="small" @click="diagnose(row)">诊断</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的提问" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- 分页 -->
    <div class="mt-2.5 flex items-center justify-between">
      <span class="text-xs text-gray-400">共 {{ total }} 条提问，当前 {{ pageFrom }}-{{ pageTo }}</span>
      <div class="flex items-center gap-2">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="store.size"
          :total="total"
          layout="prev, pager, next, jumper"
          background
          @current-change="search"
        />
        <el-select v-model="store.size" class="!w-[100px]" size="small" @change="search(0)">
          <el-option v-for="n in [10, 20, 50, 100]" :key="n" :label="`每页 ${n} 条`" :value="n" />
        </el-select>
      </div>
    </div>

    <!-- 最终回答全文弹窗（MD 渲染） -->
    <MdPreviewDialog
      v-model="answerDialog.visible"
      :title="answerDialog.title"
      :content="answerDialog.content"
      :loading="answerDialog.loading"
      empty-text="该提问暂无最终回答（未完成或回答未回填）"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import { fetchSessions, fetchSessionAnswer, fetchDetectRules, rerunDetect, fetchRerunStatus } from '@/api/monitor'
import { useMonitorTraceStore } from '@/stores/monitor'
import { useMonitorDiagnoseStore } from '@/stores/monitorDiagnose'
import MdPreviewDialog from '@/components/MdPreviewDialog.vue'

/**
 * 提问跟踪列表页（提问维度重构，2026-11-19 评审反馈：核心是每个提问的列表）：
 * 一行 = 一次提问（agentx_monitor_session），最前直出 conversation_id + 会话摘要
 * （窗口 title）；排序 = 会话创建时间降序 → 提问创建时间升序（同窗口提问按对话顺序聚在一起）；
 * ID/摘要输入任意组合回车即查，全部同步进 URL（链接可直接分享）；
 * 失败行左侧红条 + first_error 直出；「诊断」按提问聚焦；「详情」进窗口详情定位该提问。
 */
const route = useRoute()
const router = useRouter()
const store = useMonitorTraceStore()
const diagnoseStore = useMonitorDiagnoseStore()

/** 行内「AI 诊断」（提问级）：不进详情页直接触发右侧抽屉 */
function diagnose(row) {
  diagnoseStore.openDiagnose(row.conversationId, row.sessionId)
}

// 「最终回答」弹窗（MD 渲染）：列表行只带 160 字预览，点击按需拉全文
const answerDialog = reactive({ visible: false, title: '', content: '', loading: false })

async function openAnswer(row) {
  answerDialog.title = `最终回答 · Q${row.seq ?? '?'} ${(row.question || '').slice(0, 24)}`
  answerDialog.content = ''
  answerDialog.loading = true
  answerDialog.visible = true
  try {
    const data = await fetchSessionAnswer(row.sessionId)
    answerDialog.content = typeof data === 'string' ? data : (data?.answer || data?.data || '')
    if (!answerDialog.content) {
      answerDialog.content = ''
      ElMessage.warning('该提问暂无最终回答')
    }
  } catch {
    answerDialog.visible = false
    ElMessage.error('回答全文获取失败')
  } finally {
    answerDialog.loading = false
  }
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const timeRange = ref(null)
const minSeconds = ref(null)
const maxSeconds = ref(null)
const minRounds = ref(null)
const maxRounds = ref(null)
// Step3 风险筛选（store.filters.riskOnly 存 '1'/''，checkbox 用 boolean 绑定）
const riskOnlyChecked = computed({
  get: () => store.filters.riskOnly === '1',
  set: (v) => (store.filters.riskOnly = v ? '1' : '')
})
/** 规则下拉（规则注册表，进页面拉一次） */
const ruleOptions = ref([])

// ==================== V2 批量重诊断（只读回放） ====================

/** 勾选行（终态行才可勾选：running 检测会拒绝） */
const selectedRows = ref([])
const selectedTerminal = computed(() => selectedRows.value.filter((r) => isTerminalRow(r)))

function isTerminalRow(row) {
  return ['completed', 'error', 'interrupted'].includes(row.status)
}

function onSelectionChange(selection) {
  selectedRows.value = selection
}

/** 批量重诊断：确认框（提示只读回放）→ 按会话圈定投递 → 轮询一次进度 */
async function onBatchRerun() {
  const rowsSel = selectedTerminal.value
  if (!rowsSel.length) return
  const convIds = [...new Set(rowsSel.map((r) => r.conversationId))]
  try {
    await ElMessageBox.confirm(
      `将对选中的 ${rowsSel.length} 条提问（涉及 ${convIds.length} 个会话）用 v2 引擎重新检测。` +
      '过程为只读回放（不重放工具调用），旧风险记录会被替换。继续？',
      '批量重诊断',
      { confirmButtonText: '开始重诊断', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const data = await rerunDetect({ conversationIds: convIds, limit: 1000 })
    ElMessage.success(`已受理 ${data?.accepted ?? 0} 条，跳过 ${data?.skipped ?? 0} 条（非终态）`)
    // 受理后稍等片刻展示一次进度快照（完整进度可继续手动刷新列表观察）
    setTimeout(async () => {
      try {
        const st = await fetchRerunStatus()
        if (st && st.accepted > 0) {
          ElMessage.info(`重诊断进度：完成 ${st.completed} / 失败 ${st.failed} / 排队 ${st.pending}`)
          load()
        }
      } catch { /* 进度查询失败不影响主流程 */ }
    }, 3000)
    setTimeout(() => load(), 8000)
  } catch (e) {
    ElMessage.error(e?.message || '批量重诊断受理失败')
  }
}

async function loadRuleOptions() {
  if (ruleOptions.value.length) return
  try {
    const data = await fetchDetectRules()
    ruleOptions.value = data?.rules || []
  } catch {
    ruleOptions.value = []
  }
}

/** 风险筛选变更统一入口（checkbox/级别/规则） */
function onRiskFilterChange() {
  search(0)
}

const currentPage = computed({
  get: () => store.page + 1,
  set: (v) => (store.page = Math.max(0, v - 1))
})
const pageFrom = computed(() => (total.value === 0 ? 0 : store.page * store.size + 1))
const pageTo = computed(() => Math.min(total.value, (store.page + 1) * store.size))

async function load() {
  loading.value = true
  try {
    const data = await fetchSessions(store.requestParams())
    rows.value = data?.items || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

/** 查询：重置页码 → 同步 URL → 拉数据 */
function search(page = 0) {
  store.page = page
  router.replace({ query: store.toQuery() })
  load()
}

function resetFilters() {
  store.reset()
  timeRange.value = null
  minSeconds.value = null
  maxSeconds.value = null
  minRounds.value = null
  maxRounds.value = null
  search(0)
}

/** URL → 筛选恢复（分享链接直达） */
watch(
  () => route.query,
  (q) => {
    if (route.name !== 'monitor-traces') return
    store.syncFromQuery(q)
    restoreWidgets()
    loadRuleOptions()
    load()
  },
  { immediate: true }
)

function restoreWidgets() {
  timeRange.value =
    store.filters.start || store.filters.end
      ? [store.filters.start || null, store.filters.end || null]
      : null
  minSeconds.value = store.filters.minMs == null ? null : store.filters.minMs / 1000
  maxSeconds.value = store.filters.maxMs == null ? null : store.filters.maxMs / 1000
  minRounds.value = store.filters.minRounds
  maxRounds.value = store.filters.maxRounds
}

function onTimeRangeChange(range) {
  store.filters.start = range?.[0] || ''
  store.filters.end = range?.[1] || ''
  search(0)
}

function onSecondsChange() {
  store.filters.minMs = minSeconds.value == null ? null : minSeconds.value * 1000
  store.filters.maxMs = maxSeconds.value == null ? null : maxSeconds.value * 1000
  search(0)
}

function onRoundsChange() {
  store.filters.minRounds = minRounds.value
  store.filters.maxRounds = maxRounds.value
  search(0)
}

/** 详情 = 进窗口详情页并定位该提问（?sessionId= 深链，分组高亮 + 默认选中终答/失败轮） */
function goDetail(row) {
  router.push({
    path: `/monitor/traces/${encodeURIComponent(row.conversationId)}`,
    query: { sessionId: row.sessionId }
  })
}

/** 进窗口详情页并聚焦当前行提问（有 sessionId 时直达该问，避免缺失参数误聚焦） */
function goConversation(row) {
  router.push({
    path: `/monitor/traces/${encodeURIComponent(row.conversationId)}`,
    query: row.sessionId ? { sessionId: row.sessionId } : undefined
  })
}

function rowClass({ row }) {
  return (row.failRounds || 0) > 0 || row.status === 'error' ? 'row-failed' : ''
}

/** running 行耗时实时算（后端 duration_ms 终态才回填） */
function rowDurationMs(row) {
  if (row.durationMs != null) return row.durationMs
  if (row.status === 'running' && row.createdAt) {
    return Math.max(0, Date.now() - new Date(row.createdAt.replace(' ', 'T')).getTime())
  }
  return null
}

async function copyText(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label} 已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

/** 导出 CSV（当前筛选条件的全量提问，上限 5000 条） */
async function exportCsv() {
  const params = { ...store.requestParams(), page: 0, size: 5000 }
  const data = await fetchSessions(params)
  const list = data?.items || []
  if (!list.length) {
    ElMessage.warning('当前筛选无数据可导出')
    return
  }
  const header = ['conversation_id', 'conversation_title', 'session_id', 'seq', 'user_id', 'question', 'answer_preview', 'status',
    'rounds', 'fail_rounds', 'first_error', 'risk_total', 'risk_high', 'first_risk', 'detect_status',
    'prompt_tokens', 'completion_tokens', 'duration_ms', 'created_at']
  const lines = [header.join(',')]
  for (const r of list) {
    lines.push(header.map((k) => csvCell(r[toCamel(k)] ?? r[k])).join(','))
  }
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `sessions_${new Date().toISOString().slice(0, 19).replaceAll(':', '')}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success(`已导出 ${list.length} 条`)
}

function toCamel(snake) {
  return snake.replace(/_([a-z])/g, (_, c) => c.toUpperCase())
}

function csvCell(v) {
  const s = v === null || v === undefined ? '' : String(v)
  return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s
}

function shortId(id) {
  return id && id.length > 16 ? id.slice(0, 8) + '…' + id.slice(-4) : id
}

function statusText(status) {
  return { running: '进行中', completed: '完成', error: '失败', interrupted: '已中断' }[status] || status
}

function statusTag(status) {
  return { running: 'warning', completed: 'success', error: 'danger', interrupted: 'info' }[status] || 'info'
}

function formatTokens(n) {
  if (n == null) return '-'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return ms + 'ms'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

onMounted(() => {
  if (!Object.keys(route.query).length) load()
})
</script>

<style>
/* 失败行左侧红条（scoped 样式作用于 el-table 子元素需去掉 scoped） */
.el-table .row-failed {
  --el-table-tr-bg-color: #fef7f7;
}
.el-table .row-failed td:first-child {
  position: relative;
}
.el-table .row-failed td:first-child::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #ef4444;
}
</style>

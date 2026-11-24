<template>
  <div class="flex h-full flex-col overflow-auto p-4">
    <!-- 页头：时间范围切换 + 自动刷新 -->
    <div class="mb-3 flex items-center justify-between">
      <h1 class="m-0 text-[17px] font-semibold text-gray-900">监控总览</h1>
      <div class="flex items-center gap-2">
        <el-radio-group v-model="range" size="small" @change="loadAll">
          <el-radio-button value="10m">10m</el-radio-button>
          <el-radio-button value="30m">30m</el-radio-button>
          <el-radio-button value="1h">1h</el-radio-button>
          <el-radio-button value="24h">24h</el-radio-button>
          <el-radio-button value="7d">7d</el-radio-button>
        </el-radio-group>
        <el-switch v-model="autoRefresh" active-text="30s 自动刷新" size="small" />
        <span class="flex items-center gap-1 text-xs" :class="autoRefresh ? 'text-green-500' : 'text-gray-300'">
          <span class="h-1.5 w-1.5 rounded-full" :class="autoRefresh ? 'animate-pulse bg-green-500' : 'bg-gray-300'" />
        </span>
      </div>
    </div>

    <!-- KPI 卡 6 个（含 trace 写入失败数：监控表写失败兜底可观测，杜绝静默丢数据） -->
    <div class="grid grid-cols-6 gap-3">
      <div v-for="card in kpiCards" :key="card.label"
           class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm">
        <div class="text-xs text-gray-400">{{ card.label }}</div>
        <div class="mt-1.5 text-2xl font-semibold tabular-nums text-gray-900">
          {{ card.value }}
        </div>
        <div class="mt-1 text-xs" :class="card.subClass">{{ card.sub }}</div>
      </div>
    </div>

    <!-- 双趋势图 -->
    <div class="mt-3 grid grid-cols-2 gap-3">
      <div class="rounded-xl border border-gray-100 bg-white p-3 shadow-sm">
        <div class="mb-1 text-sm font-medium text-gray-700">会话 / 轮次趋势</div>
        <div ref="trendChartRef" class="h-[240px] w-full" />
      </div>
      <div class="rounded-xl border border-gray-100 bg-white p-3 shadow-sm">
        <div class="mb-1 text-sm font-medium text-gray-700">失败率 &amp; P95 耗时</div>
        <div ref="perfChartRef" class="h-[240px] w-full" />
      </div>
    </div>

    <!-- 失败提问 TOP（提问维度，点击直达详情定位该提问） -->
    <div class="mt-3 rounded-xl border border-gray-100 bg-white p-3 shadow-sm">
      <div class="mb-2 text-sm font-medium text-gray-700">失败提问 TOP 10</div>
      <el-table :data="overview?.topFailed || []" size="small" empty-text="窗口内无失败提问">
        <el-table-column label="user_id" prop="userId" width="110" show-overflow-tooltip />
        <el-table-column label="question 摘要" min-width="180">
          <template #default="{ row }">
            <span class="cursor-pointer text-gray-700 hover:text-blue-600" @click="goDetail(row)">
              {{ row.question || row.conversationId }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" type="danger" disable-transitions>{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败轮/原因" min-width="200">
          <template #default="{ row }">
            <span v-if="row.lastRound" class="text-red-500">R{{ row.lastRound }}</span>
            <span class="ml-1 text-gray-500">{{ row.lastError || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="80">
          <template #default="{ row }">{{ formatDuration(row.totalMs) }}</template>
        </el-table-column>
        <el-table-column label="发生时间" prop="lastAt" width="150" show-overflow-tooltip />
      </el-table>
    </div>

    <!-- 会话 TOP 三联：耗时 / 多轮 / 多 token（窗口内指标倒序，行点击进详情） -->
    <div class="mt-3 grid grid-cols-1 gap-3 xl:grid-cols-3">
      <div
        v-for="board in topBoards"
        :key="board.key"
        class="min-w-0 rounded-xl border border-gray-100 bg-white p-3 shadow-sm"
      >
        <div class="mb-2 text-sm font-medium text-gray-700">{{ board.title }}</div>
        <el-table :data="overview?.[board.key] || []" size="small" :empty-text="'窗口内无会话'">
          <el-table-column label="question 摘要" min-width="160">
            <template #default="{ row }">
              <span
                class="cursor-pointer text-gray-700 hover:text-blue-600"
                :title="row.question"
                @click="goDetail(row)"
              >
                {{ abbreviate(row.question, 24) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="board.metricLabel" width="86" align="right">
            <template #default="{ row }">{{ board.format(row.value) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 进行中会话（卡死检测） -->
    <div class="mb-2 mt-3 rounded-xl border border-gray-100 bg-white p-3 shadow-sm">
      <div class="mb-2 flex items-center gap-2 text-sm font-medium text-gray-700">
        进行中会话
        <span class="text-xs font-normal text-gray-400">仅 running；超过 {{ stuckMinutes }} 分钟疑似卡死</span>
      </div>
      <el-table :data="overview?.running || []" size="small" empty-text="当前没有进行中的会话">
        <el-table-column label="conv_id" width="170">
          <template #default="{ row }">
            <span class="cursor-pointer font-mono text-xs text-gray-600 hover:text-blue-600"
                  @click="goDetail(row)">{{ shortId(row.conversationId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="user" prop="userId" width="100" show-overflow-tooltip />
        <el-table-column label="已运行" width="90">
          <template #default="{ row }">{{ formatDuration(row.runningMs) }}</template>
        </el-table-column>
        <el-table-column label="轮次" prop="rounds" width="70" />
        <el-table-column label="状态" min-width="180">
          <template #default="{ row }">
            <span v-if="row.stuck" class="flex items-center gap-1 text-orange-500">
              <el-icon><WarningFilled /></el-icon> 超过 {{ stuckMinutes }} 分钟, 疑似卡死
            </span>
            <span v-else class="flex items-center gap-1 text-green-600">
              <span class="h-1.5 w-1.5 rounded-full bg-green-500" /> 正常
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.stuck" link type="danger" size="small" @click="confirmInterrupt(row)">
              置为 interrupted
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import echarts from '@/utils/echarts'
import { fetchOverview, interruptConversation } from '@/api/monitor'

/**
 * 监控总览 Dashboard（T3.5，设计 §6.1）：KPI 卡 + 双趋势图（分钟级聚合表，7d 自动降采样）
 * + 失败会话 TOP + 进行中会话卡死检测；30s 轮询一期，不引入 WebSocket（决策记录）。
 */
const router = useRouter()
const range = ref('1h')
const autoRefresh = ref(true)
const overview = ref(null)
const stuckMinutes = 10

const trendChartRef = ref(null)
const perfChartRef = ref(null)
let trendChart = null
let perfChart = null
let refreshTimer = null

const kpiCards = computed(() => {
  const k = overview.value?.kpi || {}
  const successRate = k.successRate == null ? '-' : (k.successRate * 100).toFixed(1) + '%'
  return [
    { label: '提问总数', value: formatNum(k.convs), sub: `窗口 ${rangeLabel(range.value)}`, subClass: 'text-gray-400' },
    { label: '成功率', value: successRate, sub: `失败 ${formatNum(k.failRounds)} 轮`, subClass: rateClass(k.successRate) },
    { label: '轮次总数', value: formatNum(k.rounds), sub: `失败提问 ${formatNum(k.errorConvs)}`, subClass: 'text-gray-400' },
    { label: 'P95 耗时', value: k.p95Ms == null ? '-' : formatDuration(k.p95Ms), sub: '5 分钟桶均值口径', subClass: 'text-gray-400' },
    { label: 'Tokens 消耗', value: formatTokens((k.promptTokens || 0) + (k.completionTokens || 0)),
      sub: `输入 ${formatTokens(k.promptTokens || 0)} / 输出 ${formatTokens(k.completionTokens || 0)}`, subClass: 'text-gray-400' },
    { label: 'trace 写入失败', value: formatNum(k.traceWriteFailures),
      sub: Number(k.traceWriteFailures) > 0 ? '监控表有写入失败，请查日志' : '监控表写入正常', subClass: Number(k.traceWriteFailures) > 0 ? 'text-red-500' : 'text-gray-400' }
  ]
})

function rateClass(rate) {
  if (rate == null) return 'text-gray-400'
  if (rate >= 0.95) return 'text-green-600'
  if (rate >= 0.9) return 'text-orange-500'
  return 'text-red-500'
}

async function loadAll() {
  try {
    overview.value = await fetchOverview(range.value)
    renderCharts()
  } catch {
    /* 拦截器已弹错 */
  }
}

function renderCharts() {
  const o = overview.value
  if (!o) return
  const trend = o.trend || []
  const perf = o.perf || []
  const minutes = trend.map((p) => p.minute)
  const perfMinutes = perf.map((p) => p.minute)

  if (trendChartRef.value) {
    trendChart = trendChart || echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { top: 0, right: 0, itemWidth: 14, textStyle: { fontSize: 11 } },
      grid: { left: 40, right: 16, top: 28, bottom: 40 },
      xAxis: { type: 'category', data: minutes, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      dataZoom: range.value === '7d' ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 4 }] : [],
      series: [
        { name: '会话数', type: 'line', smooth: true, showSymbol: false, data: trend.map((p) => p.convs) },
        { name: '轮次数', type: 'line', smooth: true, showSymbol: false, data: trend.map((p) => p.rounds) },
        { name: '失败轮次', type: 'line', smooth: true, showSymbol: false, itemStyle: { color: '#ef4444' },
          areaStyle: { opacity: 0.08 }, data: trend.map((p) => p.failRounds) }
      ]
    })
  }
  if (perfChartRef.value) {
    perfChart = perfChart || echarts.init(perfChartRef.value)
    perfChart.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (v) => (v == null ? '-' : v) },
      legend: { top: 0, right: 0, itemWidth: 14, textStyle: { fontSize: 11 } },
      grid: { left: 44, right: 44, top: 28, bottom: 40 },
      xAxis: { type: 'category', data: perfMinutes, axisLabel: { fontSize: 10 } },
      yAxis: [
        { type: 'value', name: '失败率(%)', axisLabel: { fontSize: 10 } },
        { type: 'value', name: 'P95(s)', axisLabel: { fontSize: 10 } }
      ],
      dataZoom: range.value === '7d' ? [{ type: 'inside' }, { type: 'slider', height: 16, bottom: 4 }] : [],
      series: [
        { name: '失败率(%)', type: 'line', smooth: true, showSymbol: false, itemStyle: { color: '#ef4444' },
          data: perf.map((p) => (p.failRate == null ? null : +(p.failRate * 100).toFixed(2))) },
        { name: 'P95(s)', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false,
          lineStyle: { type: 'dashed' },
          data: perf.map((p) => (p.p95Ms == null ? null : +(p.p95Ms / 1000).toFixed(2))) }
      ]
    })
  }
}

async function confirmInterrupt(row) {
  try {
    await ElMessageBox.confirm(
      `确认将进行中的会话 ${shortId(row.conversationId)} 置为 interrupted？该操作会中断用户正在运行的任务。`,
      '二次确认',
      { type: 'warning', confirmButtonText: '确认置为 interrupted', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await interruptConversation(row.conversationId)
    ElMessage.success('已置为 interrupted')
    loadAll()
  } catch {
    /* 拦截器已弹错 */
  }
}

function goDetail(row) {
  // sessionId 非空 = 提问维度条目（失败 TOP / 进行中），深链定位该提问
  const query = row.sessionId ? { sessionId: row.sessionId } : {}
  router.push({
    path: `/monitor/traces/${encodeURIComponent(row.conversationId)}`,
    query
  })
}

function rangeLabel(r) {
  return { '10m': '近 10 分钟', '30m': '近 30 分钟', '1h': '近 1 小时', '24h': '近 24 小时', '7d': '近 7 天' }[r] || r
}

function statusText(status) {
  return { running: '进行中', completed: '完成', error: '失败', interrupted: '已中断' }[status] || status
}

function formatNum(n) {
  return (n ?? 0).toLocaleString()
}

function formatTokens(n) {
  if (n == null) return '-'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}

function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return Math.round(ms) + 'ms'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

function shortId(id) {
  return id && id.length > 16 ? id.slice(0, 10) + '…' + id.slice(-4) : id
}

/** 会话 TOP 三联看板（耗时/多轮/多 token），value 语义随看板：ms / 轮数 / token 数 */
const topBoards = [
  { key: 'topDuration', title: '⏱ 耗时会话 TOP 10', metricLabel: '耗时', format: formatDuration },
  { key: 'topRounds', title: '🔁 多轮会话 TOP 10', metricLabel: '轮数', format: (v) => v },
  { key: 'topTokens', title: '🪙 多 token 会话 TOP 10', metricLabel: 'tokens', format: formatTokens }
]

function abbreviate(text, max) {
  const s = String(text || '')
  return s.length > max ? s.slice(0, max) + '…' : s
}

watch(autoRefresh, (on) => {
  clearInterval(refreshTimer)
  if (on) refreshTimer = setInterval(loadAll, 30_000)
})

onMounted(() => {
  loadAll()
  if (autoRefresh.value) refreshTimer = setInterval(loadAll, 30_000)
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  perfChart?.dispose()
  trendChart = null
  perfChart = null
})

function resizeCharts() {
  trendChart?.resize()
  perfChart?.resize()
}
</script>

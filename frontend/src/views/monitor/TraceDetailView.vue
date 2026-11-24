<template>
  <div class="flex h-full flex-col overflow-hidden p-4">
    <!-- 页头 -->
    <div class="mb-3 flex shrink-0 items-center justify-between gap-3">
      <div class="flex min-w-0 items-center gap-2">
        <el-button text size="small" @click="$router.push('/monitor/traces')">
          <el-icon class="mr-0.5"><Back /></el-icon>返回列表
        </el-button>
        <span class="text-[15px] font-semibold text-gray-900">提问</span>
        <span class="truncate font-mono text-xs text-gray-500" :title="conversationId">{{ conversationId }}</span>
        <el-icon class="cursor-pointer text-gray-300 hover:text-blue-500" @click="copyText(conversationId, 'conversation_id')">
          <CopyDocument />
        </el-icon>
        <el-button text size="small" type="primary" @click="shareLink">
          <el-icon class="mr-0.5"><Share /></el-icon>分享链接
        </el-button>
      </div>
    </div>

    <!-- 提问基本信息条（可折叠；关联报告 + AI 诊断固定右上角） -->
    <div class="mb-3 shrink-0 rounded-xl border border-gray-100 bg-white shadow-sm">
      <div class="flex cursor-pointer items-center gap-2 px-3.5 py-2.5" @click="infoCollapsed = !infoCollapsed">
        <el-icon class="shrink-0 text-gray-400">
          <CaretBottom v-if="!infoCollapsed" />
          <CaretRight v-else />
        </el-icon>
        <span class="shrink-0 text-sm font-medium text-gray-700">提问基本信息</span>
        <span class="shrink-0 rounded bg-blue-100 px-1.5 py-0.5 text-[11px] font-semibold text-blue-600">
          Q{{ currentSession?.seq ?? '?' }}
        </span>
        <span class="min-w-0 flex-1 truncate text-sm text-gray-800" :title="currentSession?.question">
          {{ currentSession?.question || '（无提问内容）' }}
        </span>
        <span class="shrink-0 text-xs text-gray-400">
          <el-tag size="small" :type="statusTag(currentSession?.status)" disable-transitions>
            {{ statusText(currentSession?.status) }}
          </el-tag>
          <span class="ml-2">{{ currentSession?.rounds ?? currentRounds.length }} 轮</span>
          <span v-if="(currentSession?.failRounds || 0) > 0" class="ml-1 text-red-500">失败{{ currentSession.failRounds }}</span>
          <span v-if="currentSession?.durationMs != null" class="ml-2">{{ formatDuration(currentSession.durationMs) }}</span>
          <span class="ml-2">{{ formatTokens(currentSession?.promptTokens) }} / {{ formatTokens(currentSession?.completionTokens) }} tok</span>
        </span>
        <!-- 右上角：关联报告 + AI 诊断（面板点击切换折叠，按钮区 stop 防误触） -->
        <span class="ml-2 flex shrink-0 items-center gap-1.5" @click.stop>
          <el-popover placement="bottom-end" :width="320" trigger="click">
            <template #reference>
              <el-button size="small" :type="relatedReports.length ? 'primary' : 'info'" plain>
                <el-icon class="mr-0.5"><Document /></el-icon>关联报告({{ relatedReports.length }})
              </el-button>
            </template>
            <template v-if="relatedReports.length">
              <div
                v-for="r in relatedReports"
                :key="r.id"
                class="cursor-pointer rounded-lg px-2 py-1.5 hover:bg-gray-50"
                @click="$router.push({ path: '/monitor/reports', query: { id: r.id } })"
              >
                <div class="truncate text-xs text-blue-600">📄 {{ reportTitle(r) }}</div>
                <div class="mt-0.5 text-[11px] text-gray-400">{{ r.createdAt }}</div>
              </div>
            </template>
            <div v-else class="py-1 text-xs text-gray-300">暂无诊断报告</div>
          </el-popover>
          <el-button type="warning" size="small" @click="diagnoseStore.openDiagnose(conversationId, currentSessionId)">
            AI 诊断 <el-icon class="ml-0.5"><ArrowRight /></el-icon>
          </el-button>
        </span>
      </div>
      <!-- 展开态：提问元信息 -->
      <div v-if="!infoCollapsed" class="border-t border-gray-50 px-3.5 py-3">
        <div class="space-y-2 text-xs">
          <div>
            <div class="text-gray-400">question</div>
            <div class="mt-0.5 whitespace-pre-wrap break-words text-sm leading-5 text-gray-800">
              {{ currentSession?.question || '-' }}
            </div>
          </div>
          <!-- session_id 单独一行放开 + 一键复制（排查深链常用） -->
          <div class="flex items-center gap-1">
            <span class="text-gray-400">session_id</span>
            <span class="select-all font-mono text-gray-700">{{ currentSessionId || '-' }}</span>
            <el-icon
              v-if="currentSessionId"
              class="cursor-pointer text-gray-300 hover:text-blue-500"
              title="复制 session_id"
              @click="copyText(currentSessionId, 'session_id')"
            >
              <CopyDocument />
            </el-icon>
          </div>
          <div class="flex flex-wrap items-center gap-x-8 gap-y-1.5">
            <span class="text-gray-400">
              user
              <span class="ml-1 text-gray-700">{{ currentSession?.userId || detail?.userId || '-' }}</span>
            </span>
            <span class="text-gray-400">
              状态
              <el-tag class="ml-1" size="small" :type="statusTag(currentSession?.status)" disable-transitions>
                {{ statusText(currentSession?.status) }}
              </el-tag>
            </span>
            <span class="text-gray-400">
              开始
              <span class="ml-1 text-gray-700">{{ currentSession?.createdAt || '-' }}</span>
            </span>
            <span class="text-gray-400">
              结束
              <span class="ml-1 text-gray-700">{{ currentSession?.completedAt || '-' }}</span>
            </span>
            <span class="text-gray-400">
              总耗时
              <span class="ml-1 text-gray-700">{{ formatDuration(currentSession?.durationMs) }}</span>
            </span>
            <span class="text-gray-400">
              轮次
              <span class="ml-1 text-gray-700">
                {{ currentSession?.rounds ?? currentRounds.length }}
                <span v-if="(currentSession?.failRounds || 0) > 0" class="text-red-500">(失败{{ currentSession.failRounds }})</span>
              </span>
            </span>
            <span class="text-gray-400">
              tokens
              <span class="ml-1 text-gray-700">
                {{ formatTokens(currentSession?.promptTokens) }} / {{ formatTokens(currentSession?.completionTokens) }}
              </span>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ② 检测区域（Step3 找到异常，可收缩：状态头 + 风险卡横排 + 定位深链） -->
    <div
      class="mb-3 shrink-0 rounded-xl border shadow-sm"
      :class="detectState.hasHigh ? 'border-red-200 bg-red-50/30' : 'border-gray-100 bg-white'"
    >
      <!-- 状态头（始终一行；收缩态只留这一行，点击切换展开） -->
      <div class="flex cursor-pointer items-center gap-2 px-3.5 py-2.5" @click="detectExpanded = !detectExpanded">
        <el-icon class="shrink-0 text-gray-400">
          <CaretBottom v-if="detectExpanded" />
          <CaretRight v-else />
        </el-icon>
        <!-- 四态：命中 / 无风险 / 未检测 / 检测中 -->
        <template v-if="detectState.running">
          <el-icon class="is-loading text-blue-500"><Loading /></el-icon>
          <span class="text-sm font-medium text-gray-700">自动检测</span>
          <span class="text-xs text-gray-400">检测中… 引擎 {{ detectMeta.version || 'v1' }}</span>
        </template>
        <template v-else-if="detectState.done && detectState.total > 0">
          <span class="text-base leading-none">{{ detectState.hasHigh ? '❗' : '⚠' }}</span>
          <span class="text-sm font-medium" :class="detectState.hasHigh ? 'text-red-600' : 'text-orange-500'">
            自动检测 命中 {{ detectState.total }} 条
          </span>
          <span class="text-xs text-gray-400">
            （<span v-if="detectState.high" class="text-red-500">异常 {{ detectState.high }}</span>
            <span v-if="detectState.medium">待人工确认 {{ detectState.medium }}</span>
            <span v-if="detectState.low">风险提示 {{ detectState.low }}</span>）
          </span>
        </template>
        <template v-else-if="detectState.done">
          <span class="text-base leading-none text-green-600">✓</span>
          <span class="text-sm font-medium text-gray-700">自动检测 未发现风险</span>
        </template>
        <template v-else-if="detectState.failed">
          <el-icon class="text-orange-500"><WarningFilled /></el-icon>
          <span class="text-sm font-medium text-orange-500">自动检测 检测失败（兜底任务会重试）</span>
        </template>
        <template v-else>
          <span class="text-base leading-none text-gray-300">○</span>
          <span class="text-sm font-medium text-gray-500">自动检测 未检测（历史数据）</span>
        </template>
        <span class="ml-2 text-xs text-gray-400">
          <el-button link type="primary" size="small" @click.stop="rulesDialog.open">
            规则 {{ detectMeta.ruleCount }} 条
          </el-button>
        </span>
        <span v-if="detectState.lastAt" class="text-xs text-gray-300">{{ detectState.lastAt }}</span>
        <span class="ml-auto shrink-0" @click.stop>
          <el-button size="small" type="primary" plain :loading="detectSubmitting" @click="reDetect">
            {{ detectState.done || detectState.failed ? '重新检测' : '立即检测' }}
          </el-button>
        </span>
      </div>
      <!-- 风险卡横排（仅命中态展开显示；未检测/检测中不可信不渲染） -->
      <div v-if="detectExpanded && detectState.done && risks.length" class="flex gap-2.5 overflow-x-auto border-t px-3.5 py-3">
        <div
          v-for="(risk, idx) in risks"
          :key="idx"
          class="w-[330px] shrink-0 rounded-lg border bg-white p-2.5"
          :class="risk.severity === 'high' ? 'border-red-200' : risk.severity === 'medium' ? 'border-orange-200' : 'border-yellow-200'"
        >
          <div class="flex items-center gap-1.5">
            <span class="shrink-0 text-sm">{{ risk.severity === 'high' ? '❗' : risk.severity === 'medium' ? '⚠' : '·' }}</span>
            <span
              class="shrink-0 rounded px-1 py-0.5 text-[10px] font-semibold"
              :class="risk.severity === 'high' ? 'bg-red-100 text-red-600' : risk.severity === 'medium' ? 'bg-orange-100 text-orange-600' : 'bg-yellow-100 text-yellow-600'"
            >{{ severityCn(risk.severity) }}</span>
            <span class="shrink-0 font-mono text-[11px] text-gray-400">{{ risk.ruleId }}</span>
            <span class="truncate text-xs font-medium text-gray-700" :title="risk.title">{{ risk.title }}</span>
            <el-button link type="primary" size="small" class="ml-auto shrink-0" @click="locateFromRisk(risk)">
              定位 ▸
            </el-button>
          </div>
          <div class="mt-1.5 line-clamp-2 break-words text-xs leading-4 text-gray-500" :title="risk.detail">
            {{ risk.detail }}
          </div>
          <div v-if="risk.roundsHit && risk.roundsHit.length" class="mt-1.5 flex flex-wrap items-center gap-1">
            <span
              v-for="rn in risk.roundsHit"
              :key="rn"
              class="cursor-pointer rounded bg-gray-50 px-1.5 py-0.5 text-[10px] text-gray-500 hover:bg-blue-50 hover:text-blue-600"
              title="定位到该轮"
              @click="locateRound(rn)"
            >R{{ rn }}</span>
          </div>
          <!-- 已有诊断报告角标（成本纪律：同规则先看旧报告，避免重复诊断） -->
          <div
            v-if="riskReportMap[risk.ruleId]"
            class="mt-1.5 flex cursor-pointer items-center gap-1 rounded bg-blue-50 px-1.5 py-1 text-[10px] text-blue-600 hover:bg-blue-100"
            title="点击查看该规则已有的 AI 诊断报告"
            @click="openRiskReport(risk.ruleId)"
          >
            <span>已有诊断报告 · {{ fromNow(riskReportMap[risk.ruleId].createdAt) }}</span>
            <span class="ml-auto">查看报告 →</span>
          </div>
          <!-- 规则级定向诊断入口（06 调研：仅人工点击触发，规则证据自动注入提示词） -->
          <el-button size="small" type="primary" plain class="!mt-1.5 w-full" @click="diagnoseRisk(risk)">
            ✦ AI 诊断此风险
          </el-button>
        </div>
      </div>
    </div>

    <div v-loading="loading" class="flex min-h-0 flex-1 gap-3">
      <!-- 左栏：事件流时间轴（❓提问 → 🤖 LLM 决策 + 🔧 并发工具 → 💬 终答） -->
      <section class="flex w-[420px] shrink-0 flex-col rounded-xl border border-gray-100 bg-white shadow-sm">
        <div class="flex shrink-0 items-center justify-between border-b border-gray-50 px-3 py-2">
          <div class="flex items-center gap-1.5">
            <span class="text-sm font-medium text-gray-700">事件流</span>
            <span class="text-xs text-gray-400">({{ visibleNodeCount }} 事件)</span>
          </div>
          <el-radio-group v-model="timelineFilter" size="small">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="failed">失败</el-radio-button>
            <el-radio-button value="tool">工具</el-radio-button>
            <el-radio-button value="risk">⚠ 风险</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="!timelineNodes.length && !loading" class="flex flex-1 items-center justify-center">
          <el-empty description="该提问暂无事件流" :image-size="70" />
        </div>
        <div v-else class="min-h-0 flex-1 overflow-auto px-3 py-1">
          <template v-for="node in timelineNodes" :key="'g-' + node.event.seq">
            <!-- 主事件行：❓提问 / 🤖 LLM 决策 / 💬 终答 -->
            <div
              :id="'event-node-' + node.event.seq"
              class="mt-1 cursor-pointer rounded-lg border px-3 py-2 transition-colors"
              :class="node.event.seq === selectedSeq
                ? 'border-blue-300 bg-blue-50'
                : 'border-transparent hover:bg-gray-50'"
              @click="selectEvent(node.event)"
            >
              <div class="flex items-center gap-2 text-sm">
                <!-- 折叠箭头（仅 LLM 决策有子工具） -->
                <span
                  v-if="node.children.length"
                  class="shrink-0 select-none font-mono text-xs text-gray-400 hover:text-blue-500"
                  @click.stop="toggleCollapse(node.event.seq)"
                >{{ collapsed.has(node.event.seq) ? '▸' : '▾' }}</span>
                <span class="shrink-0 text-[13px] leading-none">{{ eventIcon(node.event) }}</span>
                <span v-if="eventSuccess(node.event)" class="shrink-0 font-mono text-xs text-gray-400">
                  {{ eventTypeLabel(node.event) }}
                </span>
                <el-icon v-else :size="14" class="shrink-0 text-red-500"><CircleCloseFilled /></el-icon>
                <span class="min-w-0 flex-1 truncate text-xs" :class="eventTextClass(node.event)" :title="eventTitleFull(node.event)">
                  {{ eventTitleFull(node.event) }}
                </span>
                <el-tooltip
                  v-if="node.riskRules.length"
                  :content="`命中规则：${node.riskRules.join('、')}（点击查看异常详情）`"
                  placement="top"
                  :show-after="200"
                >
                  <span
                    class="shrink-0 text-[13px] leading-none text-orange-500 hover:text-red-500"
                    @click.stop="selectEvent(node.event, 'risk')"
                  >⚠</span>
                </el-tooltip>
                <span class="shrink-0 text-[11px] text-gray-400">{{ formatDuration(node.event.durationMs) }}</span>
              </div>
              <div class="mt-0.5 flex items-center gap-3 pl-6 text-[11px] text-gray-400">
                <span v-if="node.event.promptTokens != null">
                  {{ formatTokens(node.event.promptTokens) }}/{{ formatTokens(node.event.completionTokens) }} tok
                </span>
                <span v-if="node.event.errorMessage" class="truncate text-red-400">
                  错误: {{ node.event.errorMessage }}
                </span>
                <span v-if="node.children.length" class="shrink-0">
                  {{ node.children.length }} 个工具
                </span>
              </div>
            </div>
            <!-- 🔧 工具子事件（并发同级；结果直接内联展示） -->
            <template v-if="!collapsed.has(node.event.seq)">
              <div
                v-for="child in node.children"
                :key="'c-' + child.seq"
                :id="'event-node-' + child.seq"
                class="ml-7 cursor-pointer rounded-lg border px-3 py-1.5 transition-colors"
                :class="child.seq === selectedSeq
                  ? 'border-blue-300 bg-blue-50'
                  : 'border-transparent hover:bg-gray-50'"
                @click="selectEvent(child)"
              >
                <div class="flex items-center gap-2 text-xs">
                  <span class="shrink-0">{{ toolIcon(child) }}</span>
                  <span class="shrink-0 font-mono font-semibold text-violet-600">{{ child.toolName }}</span>
                  <span class="min-w-0 flex-1 truncate font-mono text-[11px] text-gray-400" :title="toolArgsPreview(child)">
                    {{ toolArgsPreview(child) }}
                  </span>
                  <span v-if="child.resultChars != null" class="shrink-0 text-[11px] text-gray-400">
                    {{ formatSize(child.resultChars) }}
                  </span>
                  <span v-else class="shrink-0 text-[11px] text-gray-300">结果缺</span>
                </div>
              </div>
            </template>
          </template>
        </div>
      </section>

      <!-- 右栏：事件流程快照（五 Tab：增量输入/全量输入/思考/输出·决策/执行结果） -->
      <section class="flex min-w-0 flex-1 flex-col rounded-xl border border-gray-100 bg-white shadow-sm">
        <EventSnapshot
          v-if="selectedEvent"
          :event="selectedEvent"
          :events="timelineEvents"
          :risks="risks"
          :initial-tab="eventTabOverride"
          :load-round="loadRound"
          @locate="locateSeq"
        />
        <div v-else class="flex flex-1 items-center justify-center">
          <el-empty description="点击左侧事件查看流程快照" :image-size="80" />
        </div>
      </section>
    </div>

    <!-- Step3 检测规则弹层（D7 轻量 web 管理：开关/阈值编辑/恢复默认，改后即时生效免重启） -->
    <el-dialog v-model="rulesDialog.visible" title="检测规则" width="860px" destroy-on-close>
      <div class="mb-2 text-xs text-gray-400">
        引擎 {{ detectMeta.version }}，共 {{ rulesDialog.rules.length }} 条规则；开关/阈值改动落库即时生效（影响后续检测，历史命中不自动重算）。
      </div>
      <el-table :data="rulesDialog.rules" size="small" max-height="480">
        <el-table-column label="规则" width="170">
          <template #default="{ row }">
            <span class="font-mono text-xs text-gray-600">{{ row.ruleId }}</span>
            <div class="truncate text-xs text-gray-400" :title="row.title">{{ row.title }}</div>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="70">
          <template #default="{ row }">
            <span :class="severityClass(row.severity)">{{ severityCn(row.severity) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <span class="text-xs text-gray-500">{{ row.description }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" size="small" @change="saveRule(row, { enabled: row.enabled })" />
          </template>
        </el-table-column>
        <el-table-column label="阈值" min-width="200">
          <template #default="{ row }">
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
              <label v-for="(val, key) in row.params" :key="key" class="flex items-center gap-1 text-xs text-gray-500">
                {{ key }}
                <el-input-number
                  v-if="typeof val === 'number'"
                  :model-value="val"
                  size="small"
                  :controls="false"
                  class="!w-[90px]"
                  @change="(nv) => saveRule(row, { params: { [key]: nv } }, key, nv)"
                />
                <el-input
                  v-else
                  :model-value="paramText(val)"
                  size="small"
                  class="!w-[170px]"
                  placeholder="逗号分隔"
                  @change="(nv) => saveRule(row, { params: { [key]: parseParamText(nv, val) } }, key, nv)"
                />
              </label>
              <span v-if="!Object.keys(row.params || {}).length" class="text-xs text-gray-300">—</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link size="small" @click="resetRule(row)">恢复默认</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt-2 text-[11px] text-gray-400">
        阈值列表型参数（如 failKeywords）用逗号分隔；「恢复默认」回退代码默认开关与阈值。改动在配置表留痕（updated_by/updated_at）可审计。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  Back,
  CaretBottom,
  CaretRight,
  CircleCloseFilled,
  CopyDocument,
  Document,
  Loading,
  Share,
  WarningFilled
} from '@element-plus/icons-vue'
import {
  fetchConversationDetail,
  fetchRoundDetail,
  fetchReports,
  fetchSessionRisks,
  fetchSessionRiskReports,
  fetchSessionTimeline,
  triggerDetect,
  fetchDetectRules,
  updateDetectRule
} from '@/api/monitor'
import { useMonitorDiagnoseStore } from '@/stores/monitorDiagnose'
import EventSnapshot from '@/components/snapshot/EventSnapshot.vue'

/**
 * 提问详情页（2026-11-20 排查视角改造）：左轴从「LLM 调用轮」改为事件流——
 * ❓提问 → [🤖 LLM 决策(思考+输出) → 🔧 并发工具(结果内联)]×N → 💬 终答，
 * 决策与结果闭环在同一父节点下（数据源 /monitor/sessions/{id}/timeline，后端配对）；
 * 右栏 = 事件流程快照五 Tab（增量输入/全量输入/思考/输出·决策/执行结果，EventSnapshot 组件）。
 * ② 检测区域保持不变（自动检测卡 + 风险定位深链映射到事件轮次）；
 * 当前问由深链决定：?sessionId=（可带 &round=）/?traceId=，无深链默认第一问聚焦终答。
 */
const route = useRoute()
const router = useRouter()
const diagnoseStore = useMonitorDiagnoseStore()

const conversationId = decodeURIComponent(route.params.conversationId || '')
const loading = ref(false)
const detail = ref(null)
const timelineFilter = ref('all')
const infoCollapsed = ref(true) // 基础信息默认折叠（点开看详情）
const currentSessionId = ref('')
const relatedReports = ref([]) // 右上角「关联报告」popover（T5.5 联动）

// ==================== 事件流（左轴 + 右栏快照） ====================

const timelineEvents = ref([])
const timelineCache = new Map()
const selectedSeq = ref(null)
const selectedEvent = computed(() =>
  timelineEvents.value.find((e) => e.seq === selectedSeq.value) || null)
/** 快照初始 Tab 覆写（点 ⚠ 角标 → 'risk' 直达规则异常；普通点击空串走类型默认） */
const eventTabOverride = ref('')
/** 折叠的 LLM 决策节点（seq 集合；默认全展开） */
const collapsed = ref(new Set())

const currentRounds = computed(() => {
  const sid = currentSessionId.value
  return sid ? (detail.value?.rounds || []).filter((r) => r.sessionId === sid) : []
})

/** 筛选命中集合（all = 全部） */
const visibleKeep = computed(() => {
  const evs = timelineEvents.value
  const keep = new Set(evs.map((e) => e.seq))
  if (timelineFilter.value === 'all') return keep
  keep.clear()
  const match = (e) => {
    if (timelineFilter.value === 'failed') {
      return e.type === 'tool' ? e.resultOk === false : e.success === false
    }
    if (timelineFilter.value === 'tool') return e.type === 'tool'
    if (timelineFilter.value === 'risk') return riskRoundMap.value.has(e.round)
    return true
  }
  for (const e of evs) {
    if (match(e)) keep.add(e.seq)
  }
  // 子命中保留父级；父命中保留子级（决策-结果闭环展示）
  for (const e of evs) {
    if (e.type === 'tool' && keep.has(e.seq) && e.parentSeq != null) keep.add(e.parentSeq)
  }
  for (const e of evs) {
    if (e.type !== 'tool' && keep.has(e.seq)) {
      for (const c of childToolsOf(e.seq)) keep.add(c.seq)
    }
  }
  // 提问/终答始终保留（时间轴首尾锚点）
  if (evs.length && evs[0].type === 'user') keep.add(evs[0].seq)
  const last = evs[evs.length - 1]
  if (last && last.type === 'answer') keep.add(last.seq)
  return keep
})

/** 当前提问的事件流骨架节点：主事件 + 筛选后的并发工具子事件 + 命中规则 */
const timelineNodes = computed(() => {
  const byRoundRules = riskRoundMap.value
  const keep = visibleKeep.value
  const nodes = []
  for (const ev of timelineEvents.value) {
    if (ev.type === 'tool' || !keep.has(ev.seq)) continue
    nodes.push({
      event: ev,
      children: childToolsOf(ev.seq).filter((c) => keep.has(c.seq)),
      riskRules: byRoundRules.get(ev.round) || []
    })
  }
  return nodes
})

const visibleNodeCount = computed(() =>
  timelineNodes.value.reduce((acc, n) => acc + 1 + (collapsed.value.has(n.event.seq) ? 0 : n.children.length), 0))

function childToolsOf(seq) {
  return timelineEvents.value.filter((e) => e.type === 'tool' && e.parentSeq === seq)
}

function toggleCollapse(seq) {
  if (collapsed.value.has(seq)) collapsed.value.delete(seq)
  else collapsed.value.add(seq)
}

/** 切问懒加载事件流（缓存；加载后按深链/默认规则选中事件） */
watch(currentSessionId, async (sid) => {
  selectedSeq.value = null
  timelineEvents.value = []
  if (!sid) return
  if (timelineCache.has(sid)) {
    timelineEvents.value = timelineCache.get(sid)
    selectInitialEvent()
    return
  }
  try {
    const data = await fetchSessionTimeline(sid)
    const events = Array.isArray(data?.events) ? data.events : []
    timelineCache.set(sid, events)
    if (currentSessionId.value === sid) {
      timelineEvents.value = events
      selectInitialEvent()
    }
  } catch {
    timelineEvents.value = []
  }
})

/** 选中事件（含滚动到可视区；tab 覆写用于 ⚠ 角标直达「规则异常」） */
function selectEvent(ev, tab) {
  if (!ev) return
  eventTabOverride.value = tab || ''
  selectedSeq.value = ev.seq
  requestAnimationFrame(() => {
    document.getElementById(`event-node-${ev.seq}`)?.scrollIntoView({ block: 'nearest' })
  })
}

function locateSeq(seq) {
  const ev = timelineEvents.value.find((e) => e.seq === seq)
  if (ev) selectEvent(ev)
}

/** 事件流加载后的初始选中：深链 round/traceId 优先；无深链默认第一个轮次（R1），不指向终答 */
function selectInitialEvent() {
  const evs = timelineEvents.value
  if (!evs.length) return
  const roundParam = route.query.round ? Number(route.query.round) : null
  const traceParam = route.query.traceId ? String(route.query.traceId) : ''
  let target = null
  if (traceParam) target = evs.find((e) => e.traceId === traceParam && e.type !== 'tool')
  if (!target && roundParam != null) target = evs.find((e) => e.round === roundParam && e.type === 'llm')
  if (!target) target = evs.find((e) => e.type === 'llm')
  if (!target) target = evs[0]
  selectEvent(target)
}

// ==================== 事件行渲染辅助 ====================

function eventIcon(ev) {
  return { user: '❓', llm: '🤖', answer: '💬' }[ev.type] || '·'
}

function eventTypeLabel(ev) {
  return ev.type === 'user' ? '提问' : ev.type === 'answer' ? '终答' : `R${ev.round}`
}

function eventSuccess(ev) {
  return ev.type === 'tool' ? ev.resultOk !== false : ev.success !== false
}

function eventTextClass(ev) {
  if (ev.type === 'user') return 'font-medium text-gray-800'
  if (ev.type === 'answer') return 'font-medium text-green-700'
  return ev.success === false ? 'text-red-500' : 'text-gray-600'
}

function eventTitleFull(ev) {
  if (ev.type === 'user') return ev.assistantText || '（无提问内容）'
  if (ev.type === 'answer') return ev.assistantText || '（无回答内容）'
  return ev.assistantText ? `“${firstLine(ev.assistantText)}”` : '（无文本输出，仅工具调用）'
}

function toolIcon(ev) {
  if (ev.resultOk == null) return '◌'
  if (ev.resultOk === false) return '❌'
  return ev.resultEmpty ? 'ⓘ' : '✅'
}

function toolArgsPreview(ev) {
  try {
    const args = JSON.parse(ev.arguments || '{}')
    const first = Object.entries(args)[0]
    if (!first) return '{}'
    const val = typeof first[1] === 'string' ? first[1] : JSON.stringify(first[1])
    return `${first[0]}=${abbreviate(firstLine(val), 30)}`
  } catch {
    return abbreviate(firstLine(ev.arguments || ''), 30)
  }
}

function firstLine(text) {
  return (text || '').split('\n')[0]
}

function abbreviate(text, max) {
  return (text || '').length > max ? (text || '').slice(0, max) + '…' : (text || '')
}

/** 全量输入懒加载（EventSnapshot 用；detailCache 会话内缓存避免重复拉取） */
const detailCache = new Map()
async function loadRound(traceId) {
  if (detailCache.has(traceId)) return detailCache.get(traceId)
  const vo = await fetchRoundDetail(traceId)
  if (vo) detailCache.set(traceId, vo)
  return vo
}

// ==================== Step3 自动检测（03 方案 §6.1：检测区域绑定当前提问，切 Q 懒加载缓存） ====================

/** 当前提问的风险列表（缓存 per sessionId，切问不重拉） */
const risks = ref([])
const riskCache = new Map()
const detectExpanded = ref(false) // 自动检测卡默认折叠（只留一行状态头，命中风险时点开看卡）
const detectSubmitting = ref(false)
/** 重检后覆盖 session 行的检测字段（detectStatus/riskTotal/riskHigh/firstRisk） */
const detectOverride = ref(null)
/** 规则注册表（弹层数据源；{ version, rules: [...] }）；状态头「规则 N 条」进页面即拉一次 */
const detectMeta = reactive({ version: 'v1', ruleCount: 0 })

async function loadDetectMeta() {
  try {
    const data = await fetchDetectRules()
    detectMeta.version = data?.version || 'v1'
    detectMeta.ruleCount = (data?.rules || []).length
  } catch {
    /* 总览失败不阻断，保持默认 */
  }
}

/** 四态判定（命中 / 无风险 / 未检测 / 检测中；failed 单列） */
const detectState = computed(() => {
  const sess = currentSession.value || {}
  const o = detectOverride.value
  const status = o ? o.detectStatus : sess.detectStatus
  const total = o ? o.riskTotal : (sess.riskTotal || 0)
  const high = o ? o.riskHigh : (sess.riskHigh || 0)
  const medium = risks.value.filter((r) => r.severity === 'medium').length
  const low = risks.value.filter((r) => r.severity === 'low').length
  const lastAt = risks.value.length
    ? risks.value[0].createdAt?.slice(11, 16) || ''
    : ''
  return {
    running: (sess.status || detail.value?.status) === 'running',
    done: status === 'done',
    failed: status === 'failed',
    total: total || (status === 'done' ? risks.value.length : 0),
    high: high || 0,
    medium,
    low,
    hasHigh: (high || 0) > 0 || risks.value.some((r) => r.severity === 'high'),
    lastAt
  }
})

/** 轮号 → 命中规则 ID 列表（事件流 ⚠ 角标 + 「⚠ 风险」筛选档） */
const riskRoundMap = computed(() => {
  const map = new Map()
  const push = (round, ruleId) => {
    if (round == null) return
    if (!map.has(round)) map.set(round, [])
    if (!map.get(round).includes(ruleId)) map.get(round).push(ruleId)
  }
  for (const r of risks.value) {
    push(r.round, r.ruleId)
    for (const rn of r.roundsHit || []) push(rn, r.ruleId)
  }
  return map
})

/** 切问懒加载风险（缓存；未检测的问 risks 为空数组也缓存避免重复请求）；顺带拉已有诊断报告角标 */
const riskReports = ref([]) // 每规则最新诊断报告（RiskReportRef：ruleId/reportId/summary/createdAt）
const riskReportMap = computed(() => Object.fromEntries((riskReports.value || []).map((r) => [r.ruleId, r])))

watch(currentSessionId, async (sid) => {
  detectOverride.value = null
  if (!sid) {
    risks.value = []
    riskReports.value = []
    return
  }
  // 已有报告角标：每次切问都拉最新（轻查询；诊断完成切回即见）
  fetchSessionRiskReports(sid)
    .then((list) => {
      if (currentSessionId.value === sid) riskReports.value = Array.isArray(list) ? list : []
    })
    .catch(() => {})
  if (riskCache.has(sid)) {
    risks.value = riskCache.get(sid)
    return
  }
  risks.value = []
  try {
    const data = await fetchSessionRisks(sid)
    const list = Array.isArray(data) ? data : []
    riskCache.set(sid, list)
    if (currentSessionId.value === sid) risks.value = list
  } catch {
    /* 未检测/接口异常 → 空列表 */
  }
})

/** 重新检测 / 立即检测（同步接口 <1s；完成后刷新检测区域 + 事件流角标） */
async function reDetect() {
  if (!currentSessionId.value || detectSubmitting.value) return
  detectSubmitting.value = true
  try {
    const summary = await triggerDetect(currentSessionId.value)
    detectOverride.value = {
      detectStatus: summary?.detectStatus || 'done',
      riskTotal: summary?.riskTotal || 0,
      riskHigh: summary?.riskHigh || 0
    }
    const list = await fetchSessionRisks(currentSessionId.value)
    risks.value = Array.isArray(list) ? list : []
    riskCache.set(currentSessionId.value, risks.value)
    detectExpanded.value = true
    ElMessage.success(`检测完成：命中 ${risks.value.length} 条风险`)
  } catch (e) {
    ElMessage.error(e?.message || '检测失败')
  } finally {
    detectSubmitting.value = false
  }
}

/** 风险卡「定位 ▸」/ 轮次胶囊：定位到该轮的 LLM 决策事件 */
function locateFromRisk(risk) {
  const target = (risk.round != null && risk.round) || (risk.roundsHit || [])[0]
  if (target != null) locateRound(target)
}

// ==================== 规则级 AI 诊断（06 调研：风险卡一键入口 + 成本纪律） ====================

/**
 * 「AI 诊断此风险」：已有报告先弹「查看已有报告/重新诊断」（同规则证据未变时优先看旧报告，
 * 即缓存语义）；无报告直接打开诊断抽屉（规则证据自动注入，无需手填排查问题）。
 */
async function diagnoseRisk(risk) {
  const existing = riskReportMap.value[risk.ruleId]
  if (existing) {
    try {
      await ElMessageBox.confirm(
        `${risk.ruleId} 已有 ${fromNow(existing.createdAt)}的诊断报告：\n${existing.summary || '（无摘要）'}`,
        '再次诊断确认',
        {
          confirmButtonText: '查看已有报告',
          denyButtonText: '重新诊断',
          cancelButtonText: '取消',
          showDenyButton: true,
          type: 'info'
        }
      )
      openRiskReport(risk.ruleId) // confirm：看旧报告
      return
    } catch (action) {
      if (action !== 'deny') return // 取消/关闭：什么都不做
      // deny：落到下方重新诊断
    }
  }
  diagnoseStore.openRuleDiagnose(conversationId, currentSessionId.value, risk)
}

/** 角标「查看报告 →」：跳报告中心详情 */
function openRiskReport(ruleId) {
  const existing = riskReportMap.value[ruleId]
  if (existing) {
    router.push({ path: '/monitor/reports', query: { id: String(existing.reportId) } })
  }
}

/** 相对时间（角标展示）：刚刚 / N分钟前 / N小时前 / N天前 */
function fromNow(ts) {
  if (!ts) return ''
  const ms = Date.now() - new Date(String(ts).replace(' ', 'T')).getTime()
  if (!(ms >= 0)) return ''
  if (ms < 60_000) return '刚刚'
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}分钟前`
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}小时前`
  return `${Math.floor(ms / 86_400_000)}天前`
}

function locateRound(roundNum) {
  const ev = timelineEvents.value.find((e) => e.round === roundNum && e.type === 'llm')
    || timelineEvents.value.find((e) => e.round === roundNum)
  if (!ev) {
    ElMessage.warning(`本问没有第 ${roundNum} 轮`)
    return
  }
  selectEvent(ev)
}

// ==================== 检测规则弹层（D7 轻量 web 管理） ====================

const rulesDialog = reactive({
  visible: false,
  rules: [],
  loading: false,
  // 箭头函数显式引用 rulesDialog：模板 @click="rulesDialog.open" 是裸方法引用，this 会丢
  open: async () => {
    rulesDialog.visible = true
    rulesDialog.loading = true
    try {
      const data = await fetchDetectRules()
      rulesDialog.rules = data?.rules || []
      detectMeta.version = data?.version || 'v1'
      detectMeta.ruleCount = rulesDialog.rules.length
    } catch {
      ElMessage.error('规则注册表获取失败')
    } finally {
      rulesDialog.loading = false
    }
  }
})

/** 保存单条规则改动（开关/单个阈值键；PUT 后用返回的合并配置回填该行） */
async function saveRule(row, payload, paramKey, paramVal) {
  try {
    const updated = await updateDetectRule(row.ruleId, payload)
    Object.assign(row, updated)
    ElMessage.success(`${row.ruleId} 已更新（影响后续检测）`)
  } catch (e) {
    // 失败回滚该行显示（重新拉注册表最稳）
    rulesDialog.open()
    ElMessage.error(e?.message || `${row.ruleId} 更新失败${paramKey ? `（${paramKey}=${paramVal}）` : ''}`)
  }
}

/** 恢复默认 = 开关回代码默认 + 阈值全量回默认（覆盖配置表行） */
async function resetRule(row) {
  await saveRule(row, { enabled: row.defaultEnabled, params: row.defaultParams || {} })
}

/** 列表型参数渲染/提交（逗号分隔文本 ⇄ 数组） */
function paramText(val) {
  return Array.isArray(val) ? val.join(',') : String(val)
}

function parseParamText(text, original) {
  const raw = (text || '').trim()
  if (Array.isArray(original)) return raw ? raw.split(',').map((s) => s.trim()).filter(Boolean) : []
  return raw
}

/** V2-D9 三档口径：异常(红) / 待人工确认(橙) / 风险提示(黄) */
function severityCn(sev) {
  return { high: '异常', medium: '待人工确认', low: '风险提示' }[sev] || sev
}

function severityClass(sev) {
  return { high: 'text-red-600', medium: 'text-orange-500', low: 'text-yellow-600' }[sev] || ''
}

const sessions = computed(() => detail.value?.sessions || [])
/** 当前聚焦的提问（单问固定：事件流与基本信息条都只渲染它） */
const currentSession = computed(() =>
  sessions.value.find((s) => s.sessionId === currentSessionId.value) || null)

async function load() {
  loading.value = true
  try {
    detail.value = await fetchConversationDetail(conversationId)
    locateInitialSession()
    loadRelatedReports()
  } finally {
    loading.value = false
  }
}

/** 关联报告联动（T5.5）：该会话已生成的诊断报告，popover 点击进报告中心详情 */
async function loadRelatedReports() {
  try {
    const data = await fetchReports({ conversationId, page: 0, size: 10 })
    relatedReports.value = data?.items || []
  } catch {
    relatedReports.value = []
  }
}

/**
 * 深链定位：?sessionId=（可带 &round=）/?traceId=/?status=failed 先换算出目标提问
 * （= 当前问），事件流加载后 selectInitialEvent 再按 round/traceId 精确定位事件。
 * 无任何深链参数时默认第一问。
 */
function locateInitialSession() {
  const rs = detail.value?.rounds || []
  if (!rs.length) return
  let sessionId = null
  const traceIdParam = route.query.traceId ? String(route.query.traceId) : ''
  if (traceIdParam) {
    sessionId = rs.find((r) => r.traceId === traceIdParam)?.sessionId
  }
  const sessionParam = route.query.sessionId ? String(route.query.sessionId) : ''
  if (!sessionId && sessionParam) sessionId = sessionParam
  if (!sessionId && route.query.status === 'failed') {
    sessionId = rs.find((r) => !r.success)?.sessionId
  }
  if (!sessionId) {
    sessionId = sessions.value[0]?.sessionId || rs[0].sessionId
  }
  currentSessionId.value = sessionId
}

async function copyText(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label} 已复制`)
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

/** 分享链接 = 当前地址带 sessionId+round 深链参数，打开自动定位并高亮该轮事件 */
async function shareLink() {
  const url = new URL(window.location.href)
  url.searchParams.set('conversationId', conversationId)
  if (selectedEvent.value) {
    url.searchParams.set('sessionId', String(currentSessionId.value || ''))
    url.searchParams.set('round', String(selectedEvent.value.round))
  }
  await copyText(url.toString(), '分享链接')
}

function reportTitle(r) {
  return r.type === 'daily' ? `日报 ${r.periodStart || ''}` : `诊断报告 · ${(r.summaryMd || '').slice(0, 24)}`
}

function statusText(status) {
  return { running: '进行中', completed: '完成', error: '失败', interrupted: '已中断' }[status] || status || '-'
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
  if (ms < 1000) return Math.round(ms) + 'ms'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

function formatSize(chars) {
  if (chars >= 1024 * 1024) return (chars / 1024 / 1024).toFixed(1) + 'MB'
  if (chars >= 1024) return (chars / 1024).toFixed(1) + 'KB'
  return chars + 'B'
}

onMounted(() => {
  load()
  loadDetectMeta()
})
onBeforeUnmount(() => {
  detailCache.clear()
  riskCache.clear()
  timelineCache.clear()
})
</script>

<template>
  <el-drawer
    :model-value="diagnoseStore.open"
    direction="rtl"
    size="520px"
    :close-on-click-modal="false"
    :append-to-body="true"
    @close="onClose"
  >
    <template #header>
      <div class="flex items-center gap-2">
        <span class="text-[15px] font-semibold text-gray-900">AI 诊断</span>
        <el-tag v-if="diagnoseStore.ruleId" size="small" type="danger" disable-transitions>
          规则级 · {{ diagnoseStore.ruleId }}
        </el-tag>
        <el-tag v-else-if="diagnoseStore.sessionId" size="small" type="warning" disable-transitions>
          提问级
        </el-tag>
        <span class="font-mono text-xs text-gray-400" :title="diagnoseStore.conversationId">
          {{ shortId(diagnoseStore.conversationId) }}
        </span>
        <span v-if="diagnoseStore.sessionId" class="font-mono text-xs text-gray-400"
              :title="diagnoseStore.sessionId">· {{ shortId(diagnoseStore.sessionId) }}</span>
      </div>
    </template>

    <div class="flex h-full flex-col gap-3 overflow-hidden">
      <!-- 规则级：自动注入的排查上下文（只读展示，数据随点击带自风险卡，不重复请求） -->
      <div
        v-if="diagnoseStore.rule"
        class="shrink-0 rounded-lg border border-blue-100 bg-blue-50/60 px-2.5 py-2 text-xs leading-5 text-gray-600"
      >
        <div class="flex items-center gap-1.5">
          <span class="font-mono text-[11px] font-medium text-blue-600">{{ diagnoseStore.rule.ruleId }}</span>
          <span class="font-medium text-gray-700">{{ diagnoseStore.rule.title }}</span>
          <span :class="severityClass(diagnoseStore.rule.severity)" class="text-[11px]">
            {{ severityCn(diagnoseStore.rule.severity) }}
          </span>
          <span v-if="roundsText" class="ml-auto font-mono text-[10px] text-gray-400">命中 {{ roundsText }}</span>
        </div>
        <div class="mt-1 line-clamp-2 break-words text-gray-500" :title="diagnoseStore.rule.detail">
          {{ diagnoseStore.rule.detail }}
        </div>
        <div class="mt-0.5 text-[10px] text-gray-400">以上为规则引擎自动注入的排查上下文（AI 将围绕该证据定向诊断）</div>
      </div>

      <!-- 诊断模型：空 = 诊断默认（yml 专用诊断模型）；非空 = 模型管理里的启用模型（清单同聊天侧） -->
      <div class="shrink-0">
        <div class="mb-1 text-xs font-medium text-gray-600">诊断模型</div>
        <el-select
          v-model="diagnoseStore.model"
          class="w-full"
          placeholder="诊断默认（跟随后端配置）"
          :disabled="phase === 'running'"
        >
          <el-option value="" label="诊断默认（跟随后端配置）" />
          <el-option v-for="m in chatModels" :key="m.code" :value="m.code" :label="m.name">
            <div class="flex flex-col py-0.5">
              <div class="flex items-center gap-1.5">
                <span>{{ m.name }}</span>
                <span v-if="m.isDefault" class="rounded bg-gray-100 px-1 text-[10px] text-gray-400">默认</span>
              </div>
              <span class="text-xs text-gray-400">{{ m.remark || m.provider }}</span>
            </div>
          </el-option>
        </el-select>
        <div class="mt-1 text-[11px] leading-4 text-gray-400">{{ modelDescText }}</div>
      </div>

      <!-- 补充说明（选填）：规则级 = 围绕证据的定向追问；普通 = 留空默认全量体检 -->
      <div class="shrink-0">
        <el-input
          v-model="diagnoseStore.question"
          type="textarea"
          :rows="2"
          maxlength="500"
          resize="none"
          :disabled="phase === 'running'"
          :placeholder="diagnoseStore.ruleId
            ? '选填：补充你想重点排查的方向（如「确认接口是否持续 500」）。留空则围绕规则证据定向诊断。'
            : '选填：描述你要排查的问题，如「备案率算出来不对，帮我定位是哪一轮算错的」。留空则按默认口径全面体检。'"
        />
      </div>

      <!-- 状态行 -->
      <div class="flex shrink-0 items-center gap-2 text-xs text-gray-500">
        <template v-if="phase === 'idle'">
          <span class="h-2 w-2 rounded-full bg-gray-300" />
          待开始 · 填好排查问题（可留空）后点击右侧按钮
          <el-button type="primary" size="small" class="ml-auto" @click="start">开始诊断</el-button>
        </template>
        <template v-else-if="phase === 'running'">
          <span class="h-2 w-2 animate-pulse rounded-full bg-blue-500" />
          诊断中 · 已用 {{ elapsedText }} · 已调用 {{ toolCalls.length }} 个工具
        </template>
        <template v-else-if="phase === 'done'">
          <span class="h-2 w-2 rounded-full bg-green-500" />
          诊断完成 · 用时 {{ elapsedText }}
          <el-button v-if="reportId" link type="primary" size="small" @click="openReport">
            打开报告 →
          </el-button>
        </template>
        <template v-else>
          <span class="h-2 w-2 rounded-full bg-red-500" />
          诊断失败/已停止
          <el-button v-if="reportId" link type="primary" size="small" @click="openReport">
            查看报告 →
          </el-button>
        </template>
        <el-button v-if="phase === 'running'" link type="danger" size="small" class="ml-auto" @click="abort">
          停止
        </el-button>
        <el-button v-else link size="small" class="ml-auto" @click="restart">重新诊断</el-button>
      </div>

      <!-- 流式内容（滚动跟随） -->
      <div ref="streamRef" class="min-h-0 flex-1 space-y-3 overflow-auto pr-1" @scroll="onScroll">
        <!-- 思考 -->
        <div v-if="thinkingText">
          <div class="mb-1 flex items-center gap-1 text-xs font-medium text-gray-500">
            🧠 思考
          </div>
          <div class="rounded-lg bg-violet-50 p-2.5 text-xs leading-5 whitespace-pre-wrap text-violet-900">
            {{ thinkingText }}
          </div>
        </div>

        <!-- 工具调用 -->
        <div v-if="toolCalls.length">
          <div class="mb-1 text-xs font-medium text-gray-500">🛠 工具调用</div>
          <div class="space-y-1.5">
            <div
              v-for="(call, i) in toolCalls"
              :key="i"
              class="cursor-pointer rounded-lg border border-gray-100 bg-gray-50 px-2.5 py-1.5 text-xs"
              @click="call.expanded = !call.expanded"
            >
              <div class="flex items-center gap-1.5">
                <span v-if="call.success === true" class="text-green-600">✅</span>
                <span v-else-if="call.success === false" class="text-red-500">❌</span>
                <span v-else class="animate-spin">⟳</span>
                <span class="font-mono font-medium text-gray-700">{{ call.toolName }}</span>
                <span class="ml-auto text-gray-400">{{ call.expanded ? '收起' : '展开' }}</span>
              </div>
              <div v-if="call.arguments && !call.expanded" class="mt-1 truncate text-gray-400">
                {{ call.arguments }}
              </div>
              <div v-if="call.expanded && call.arguments" class="mt-1 break-all text-gray-500">
                入参：{{ call.arguments }}
              </div>
              <div v-if="call.expanded && call.result" class="mt-1 break-all text-gray-500">
                结果：{{ call.result }}
              </div>
              <div v-else-if="!call.expanded && call.result" class="mt-1 truncate text-gray-400">
                └ {{ call.result }}
              </div>
            </div>
          </div>
        </div>

        <!-- 阶段结论（markdown 流式） -->
        <div v-if="stageText">
          <div class="mb-1 text-xs font-medium text-gray-500">▼ 阶段结论（实时更新）</div>
          <div class="report-md report-md--compact rounded-lg border border-gray-200/80 bg-white p-3.5" v-html="renderedStage" />
        </div>

        <el-empty
          v-if="!thinkingText && !toolCalls.length && !stageText"
          :description="phase === 'running' ? '等待诊断事件…' : phase === 'idle' ? '点击「开始诊断」启动 AI 排查' : '无诊断内容'"
          :image-size="70"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { openDiagnoseStream } from '@/api/monitor'
import { listChatModels } from '@/api/model'
import { useMonitorDiagnoseStore } from '@/stores/monitorDiagnose'
import { renderReportMarkdown } from '@/utils/reportMarkdown'
import '@/assets/report-markdown.css'

/**
 * AI 诊断抽屉（T5.3，设计 §6.5）：SSE 流式渲染（thinking / tool_call / stage 三类过程事件，
 * 结束后 report_id/done）；滚动自动跟随（用户上滚则暂停跟随）；
 * 完成后可一键打开报告中心详情（证据深链在报告中心做）。
 * 诊断模型选择器（model）：空 = 诊断默认（后端 yml 专用诊断模型，temperature=0）；
 * 非空 = 模型管理里的启用模型（清单同聊天侧 /agent/models，自定义模型在「模型管理」中维护）。
 */
const router = useRouter()
const diagnoseStore = useMonitorDiagnoseStore()

const phase = ref('idle') // idle | running | done | error
const thinkingText = ref('')
const stageText = ref('')
const toolCalls = ref([])
const reportId = ref(null)
const elapsedMs = ref(0)
const streamRef = ref(null)
const followScroll = ref(true)
const chatModels = ref([]) // 可选诊断模型清单（/agent/models，同聊天侧；自定义模型在「模型管理」中添加）

let modelsLoaded = false
function ensureModels() {
  if (modelsLoaded) return
  modelsLoaded = true
  listChatModels()
    .then((list) => {
      chatModels.value = Array.isArray(list) ? list : []
      // 自愈：上次选中的模型已被删除/停用时回退「诊断默认」（同 ChatView currentModel 口径）
      if (diagnoseStore.model && !chatModels.value.some((m) => m.code === diagnoseStore.model)) {
        diagnoseStore.model = ''
      }
    })
    .catch(() => { modelsLoaded = false }) // 拉取失败不锁死，下次打开抽屉重试
}

let aborter = null
let timer = null
let startedAt = 0
// tool_call 事件流：TOOL_START 先建行（带参数），TOOL_END 按 toolName 就近补结果
let pendingByTool = null

const renderedStage = computed(() =>
  renderReportMarkdown(stageText.value, {
    stripHeaderMeta: false,
    enableRoundLinks: false
  })
)
const elapsedText = computed(() => formatDuration(elapsedMs.value))

// ── 诊断模型（选择器说明行） ──
const modelDescText = computed(() => {
  if (!diagnoseStore.model) {
    return '默认用后端专用诊断模型（temperature=0，可复现）；自定义模型在「模型管理」中添加后即出现在这里'
  }
  const hit = chatModels.value.find((m) => m.code === diagnoseStore.model)
  return hit ? (hit.remark || hit.provider || hit.code) : diagnoseStore.model
})

// ── 规则级上下文卡（数据来自风险卡点击带入的 store.rule） ──
const roundsText = computed(() => {
  const hits = diagnoseStore.rule?.roundsHit
  if (hits && hits.length) return hits.map((n) => `R${n}`).join(' ')
  return diagnoseStore.rule?.round ? `R${diagnoseStore.rule.round}` : ''
})

function severityCn(sev) {
  return { high: '异常', medium: '待人工确认', low: '风险提示' }[sev] || sev
}

function severityClass(sev) {
  return { high: 'text-red-600', medium: 'text-orange-500', low: 'text-yellow-600' }[sev] || ''
}

watch(
  () => diagnoseStore.open,
  (open) => {
    if (open) {
      // 打开抽屉 → 停在表单态：用户可先选诊断模型、填排查问题再点「开始诊断」（不自动开跑）
      reset()
      ensureModels()
    }
  }
)

function start() {
  reset()
  phase.value = 'running'
  startedAt = Date.now()
  timer = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt
  }, 500)
  pendingByTool = new Map()
  aborter = openDiagnoseStream(
    diagnoseStore.conversationId,
    handleEvent,
    {
      onError: (err) => {
        if (phase.value === 'running') {
          phase.value = 'error'
          stageText.value += `\n\n> ⚠ ${err.message || '连接中断'}`
        }
        cleanupTimer()
      },
      onClose: () => {
        if (phase.value === 'running') {
          phase.value = 'done'
        }
        cleanupTimer()
      }
    },
    diagnoseStore.question,
    diagnoseStore.sessionId,
    diagnoseStore.ruleId,
    diagnoseStore.model
  )
}

function handleEvent({ event, data }) {
  switch (event) {
    case 'thinking':
      thinkingText.value += data?.content || ''
      break
    case 'tool_call': {
      const name = data?.toolName || ''
      if (data?.arguments && data.success === null && data.content === null) {
        // TOOL_START：新行（参数摘要）
        const call = reactive({
          toolName: name,
          arguments: truncate(data.arguments, 160),
          result: '',
          success: null,
          expanded: false
        })
        toolCalls.value.push(call)
        pendingByTool.set(name, call)
      } else {
        // TOOL_END：就近补结果（同名最近未完成行，否则新行）
        const call = pendingByTool.get(name) && pendingByTool.get(name).success === null
          ? pendingByTool.get(name)
          : null
        if (call) {
          call.result = truncate(data?.content || '', 400)
          call.success = !!data?.success
          pendingByTool.delete(name)
        } else {
          toolCalls.value.push(reactive({
            toolName: name,
            arguments: '',
            result: truncate(data?.content || '', 400),
            success: !!data?.success,
            expanded: false
          }))
        }
      }
      break
    }
    case 'stage':
      stageText.value += data?.content || ''
      break
    case 'report_id':
      reportId.value = data?.reportId || null
      if (data?.elapsedMs) elapsedMs.value = data.elapsedMs
      break
    case 'done':
      phase.value = 'done'
      if (data?.elapsedMs) elapsedMs.value = data.elapsedMs
      cleanupTimer()
      break
    case 'error':
      phase.value = 'error'
      stageText.value += `\n\n> ⚠ ${data?.content || '诊断出错'}`
      cleanupTimer()
      break
    default:
      break
  }
  follow()
}

/** 滚动跟随：用户未上滚时贴底 */
function follow() {
  if (!followScroll.value || !streamRef.value) return
  requestAnimationFrame(() => {
    const el = streamRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function onScroll() {
  const el = streamRef.value
  if (!el) return
  followScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function abort() {
  aborter?.abort()
  phase.value = 'error'
  cleanupTimer()
}

function restart() {
  start()
}

function reset() {
  aborter?.abort()
  thinkingText.value = ''
  stageText.value = ''
  toolCalls.value = []
  reportId.value = null
  elapsedMs.value = 0
  followScroll.value = true
  phase.value = 'idle'
  cleanupTimer()
}

function cleanupTimer() {
  clearInterval(timer)
  timer = null
}

function onClose() {
  diagnoseStore.close()
  // 已完成的诊断不中断连接（SSE 已结束）；进行中的停止本地消费
  if (phase.value === 'running') {
    aborter?.abort()
    phase.value = 'error'
    cleanupTimer()
  }
}

function openReport() {
  diagnoseStore.close()
  router.push({ path: '/monitor/reports', query: { id: String(reportId.value) } })
}

function shortId(id) {
  return id && id.length > 16 ? id.slice(0, 10) + '…' + id.slice(-4) : id
}

function truncate(text, max) {
  if (!text) return ''
  const oneLine = String(text).replace(/\s+/g, ' ').trim()
  return oneLine.length > max ? oneLine.slice(0, max) + '…' : oneLine
}

function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return Math.round(ms) + 'ms'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  const m = Math.floor(s / 60)
  return `${m}m${Math.round(s % 60)}s`
}

onBeforeUnmount(() => {
  aborter?.abort()
  cleanupTimer()
})
</script>

<template>
  <aside
    v-if="visible && tabs.length"
    class="sub-drawer"
    :class="{ 'is-expanded': expanded }"
  >
    <!-- 顶部多 Tab 标签栏（对标参考文档图 4） -->
    <div class="drawer-tabbar">
      <div class="drawer-tabs thin-scroll">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="drawer-tab"
          :class="{ active: tab.key === activeKey }"
          :title="`${tab.label}（${tab.stateLabel}）`"
          @click="$emit('select', tab.key)"
        >
          <span class="tab-dot" :class="`dot-${tab.state}`"></span>
          <span class="tab-label">{{ tab.label }}</span>
        </button>
      </div>
      <div class="drawer-actions">
        <button
          type="button"
          class="drawer-icon-btn"
          :title="expanded ? '还原宽度' : '加宽查看'"
          @click="expanded = !expanded"
        >
          <el-icon :size="14"><FullScreen /></el-icon>
        </button>
        <button
          type="button"
          class="drawer-icon-btn"
          title="关闭子会话面板"
          @click="$emit('close')"
        >
          <el-icon :size="14"><Close /></el-icon>
        </button>
      </div>
    </div>

    <!-- 主体可滚动内容区 -->
    <div v-if="activeStep" class="drawer-body thin-scroll">
      <!-- 模型标识 -->
      <div class="drawer-model-bar">
        <span class="model-line"></span>
        <span class="model-text">正在使用 {{ activeModelName }}</span>
        <span class="model-line"></span>
      </div>

      <!-- 主 Agent 派发的任务 Prompt 气泡 -->
      <div v-if="taskPrompt" class="drawer-prompt-card">
        <div class="prompt-header">
          <span class="prompt-badge">{{ roleTag }}</span>
          <span class="prompt-title">委派任务指令</span>
          <button
            v-if="taskPrompt.length > 180"
            type="button"
            class="prompt-toggle"
            @click="promptOpen = !promptOpen"
          >
            {{ promptOpen ? '收起' : '展开全文' }}
          </button>
        </div>
        <div class="prompt-body" :class="{ clamped: !promptOpen && taskPrompt.length > 180 }">
          {{ taskPrompt }}
        </div>
      </div>

      <!-- 子 Agent 内部执行流水（时间线） -->
      <div class="drawer-stream-section">
        <button
          type="button"
          class="stream-summary-head"
          @click="stepsCollapsed = !stepsCollapsed"
        >
          <el-icon :size="12" class="transition-transform duration-150" :class="stepsCollapsed ? '-rotate-90' : ''">
            <ArrowDown />
          </el-icon>
          <span>已查阅 {{ stats.readCount }} 项，已执行 {{ stats.execCount }} 步</span>
          <span v-if="stats.thinkCount > 0" class="stream-summary-sub">· 思考 {{ stats.thinkCount }} 次</span>
          <span v-if="stats.failCount > 0" class="stream-summary-err">· {{ stats.failCount }} 项失败</span>
        </button>

        <div v-if="!stepsCollapsed" class="stream-timeline">
          <div v-if="!subSteps.length" class="stream-empty">
            <template v-if="outcome.state === 'running'">
              <el-icon :size="13" class="is-loading text-blue-500"><Loading /></el-icon>
              <span>正在等待子专员上报第一步动作…</span>
            </template>
            <template v-else>
              <span>该子专员未产生中间工具调用，直接返回了结果。</span>
            </template>
          </div>

          <template v-for="(s, idx) in subSteps" :key="idx">
            <!-- 思考节点 -->
            <div v-if="s.type === 'thinking'" class="tl-think">
              <button type="button" class="tl-think-head" @click="toggleSubItem(`t-${idx}`)">
                <el-icon v-if="isLiveSubThink(idx)" :size="12" class="is-loading text-blue-500"><Loading /></el-icon>
                <span v-else class="tl-think-icon">🧠</span>
                <span class="tl-think-label">
                  {{ isLiveSubThink(idx) ? '正在思考' : thinkDurationLabel(s) }}
                </span>
                <el-icon :size="11" class="ml-auto transition-transform duration-150" :class="isItemOpen(`t-${idx}`) ? 'rotate-180' : ''">
                  <ArrowDown />
                </el-icon>
              </button>
              <div v-if="isItemOpen(`t-${idx}`)" class="tl-think-body">{{ s.content }}</div>
            </div>

            <!-- 工具节点 -->
            <div v-else-if="s.type === 'tool'" class="tl-tool">
              <div
                class="tl-tool-row"
                :class="{ open: isItemOpen(`u-${idx}`), failed: s.status === 'failed' }"
                @click="toggleSubItem(`u-${idx}`)"
              >
                <span class="tl-tool-status">
                  <el-icon v-if="s.status === 'running'" :size="12" class="is-loading text-blue-500"><Loading /></el-icon>
                  <span v-else-if="s.status === 'failed'" class="text-red-500">✗</span>
                  <span v-else-if="s.status === 'interrupted'" class="text-gray-400">⏸</span>
                  <span v-else class="text-emerald-500">✓</span>
                </span>
                <span class="tl-tool-verb">{{ isReadLikeTool(s.toolName) ? '已读取' : '已执行' }}</span>
                <span class="tl-tool-name">{{ toolLabelFn(s.toolName) }}</span>
                <span v-if="argPreviewFn(s.arguments)" class="tl-tool-arg font-mono">
                  {{ argPreviewFn(s.arguments) }}
                </span>
                <span v-if="subToolDur(s)" class="tl-tool-dur font-mono">{{ subToolDur(s) }}</span>
              </div>
              <div v-if="isItemOpen(`u-${idx}`)" class="tl-tool-detail">
                <div v-if="s.arguments" class="tl-detail-block">
                  <div class="tl-detail-title">调用参数</div>
                  <pre class="tl-detail-pre thin-scroll">{{ formatJsonFn(s.arguments) }}</pre>
                </div>
                <div v-if="s.result" class="tl-detail-block">
                  <div class="tl-detail-title">返回结果</div>
                  <pre class="tl-detail-pre thin-scroll">{{ formatResultFn(s.result) }}</pre>
                </div>
              </div>
            </div>

            <!-- 子专员自己的正文叙述 -->
            <div v-else-if="s.type === 'text' && s.content?.trim()" class="tl-text">
              <div class="tl-text-tag">专员输出</div>
              <div class="report-md text-[13px] leading-relaxed text-gray-700" v-html="renderMdFn(s.content)"></div>
            </div>
          </template>
        </div>
      </div>

      <!-- 最终返回给主会话的汇报（终态时展示） -->
      <div v-if="outcome.state !== 'running' && finalReportText" class="drawer-result-box" :class="`res-${outcome.state}`">
        <div class="result-box-head">
          <span>{{ resultBoxTitle }}</span>
          <button
            v-if="finalReportText.length > 360"
            type="button"
            class="prompt-toggle"
            @click="resultOpen = !resultOpen"
          >
            {{ resultOpen ? '收起' : '展开全部' }}
          </button>
        </div>
        <div
          v-if="outcome.state === 'completed'"
          class="report-md result-box-body text-[13px] leading-relaxed"
          :class="{ clamped: !resultOpen && finalReportText.length > 360 }"
          v-html="renderMdFn(finalReportText)"
        ></div>
        <pre v-else class="result-box-pre thin-scroll">{{ finalReportText }}</pre>
      </div>
    </div>

    <!-- 底部实时状态栏（对标参考图 4 底部） -->
    <div v-if="activeStep" class="drawer-footer">
      <div class="footer-left">
        <template v-if="outcome.state === 'running'">
          <el-icon :size="13" class="is-loading text-blue-500"><Loading /></el-icon>
          <span class="truncate">
            {{ stats.runningTool ? `正在执行 ${toolLabelFn(stats.runningTool.toolName)}` : '正在思考与规划…' }}
          </span>
        </template>
        <template v-else-if="outcome.state === 'completed'">
          <el-icon :size="13" class="text-emerald-500"><CircleCheckFilled /></el-icon>
          <span>已完成 · 共 {{ stats.totalTools }} 步</span>
        </template>
        <template v-else-if="outcome.state === 'timeout'">
          <el-icon :size="13" class="text-amber-500"><WarningFilled /></el-icon>
          <span>执行超时，已自动终止</span>
        </template>
        <template v-else-if="outcome.state === 'failed'">
          <el-icon :size="13" class="text-red-500"><CircleCloseFilled /></el-icon>
          <span>执行失败</span>
        </template>
        <template v-else>
          <el-icon :size="13" class="text-gray-400"><VideoPause /></el-icon>
          <span>已随主任务停止</span>
        </template>
      </div>
      <div class="footer-right font-mono">
        <template v-if="outcome.state === 'running'">工作中 {{ footerDuration }}</template>
        <template v-else-if="footerDuration">耗时 {{ footerDuration }}</template>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  ArrowDown, CircleCheckFilled, CircleCloseFilled, Close, FullScreen, Loading, VideoPause, WarningFilled
} from '@element-plus/icons-vue'
import {
  detectSubAgentOutcome,
  extractSubTaskPrompt,
  formatSubDuration,
  isReadLikeTool,
  subAgentDisplayName,
  subAgentRoleTag,
  subStepStats
} from '@/utils/agentNames'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 当前消息中所有子 Agent 项：[{ key, step }] */
  items: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  nowTick: { type: Number, default: 0 },
  modelName: { type: String, default: '默认模型' },
  toolLabelFn: { type: Function, default: (name) => name || 'unknown' },
  argPreviewFn: { type: Function, default: () => '' },
  formatJsonFn: { type: Function, default: (s) => s || '' },
  formatResultFn: { type: Function, default: (s) => s || '' },
  renderMdFn: { type: Function, default: (s) => s || '' }
})

defineEmits(['select', 'close'])

const expanded = ref(false)
const promptOpen = ref(false)
const resultOpen = ref(false)
const stepsCollapsed = ref(false)
const openMap = reactive({})

watch(() => props.activeKey, () => {
  promptOpen.value = false
  resultOpen.value = false
  stepsCollapsed.value = false
})

/** 生成顶部 Tab 列表（同专员出现多次时自动追加 #1/#2 区分） */
const tabs = computed(() => {
  const counts = {}
  for (const it of props.items) {
    const base = subAgentDisplayName(it.step)
    counts[base] = (counts[base] || 0) + 1
  }
  const seen = {}
  return props.items.map((it) => {
    const base = subAgentDisplayName(it.step)
    seen[base] = (seen[base] || 0) + 1
    const label = counts[base] > 1 ? `${base} #${seen[base]}` : base
    const out = detectSubAgentOutcome(it.step)
    const stateLabel = {
      running: '运行中',
      completed: '已完成',
      timeout: '已超时',
      failed: '执行失败',
      interrupted: '已中断'
    }[out.state] || '执行中'
    return { key: it.key, label, state: out.state, stateLabel, step: it.step }
  })
})

const activeEntry = computed(() => {
  return props.items.find((it) => it.key === props.activeKey) || props.items[0] || null
})

const activeStep = computed(() => activeEntry.value?.step || null)
const subSteps = computed(() => activeStep.value?.subSteps || [])
const stats = computed(() => subStepStats(subSteps.value))
const outcome = computed(() => detectSubAgentOutcome(activeStep.value))
const taskPrompt = computed(() => extractSubTaskPrompt(activeStep.value?.arguments))
const roleTag = computed(() => subAgentRoleTag(activeStep.value))
const activeModelName = computed(() => activeStep.value?.modelName || props.modelName || '默认模型')

const footerDuration = computed(() => {
  if (!activeStep.value) return ''
  if (outcome.value.state === 'running') {
    if (!activeStep.value.startAt) return ''
    return formatSubDuration(Math.max(0, props.nowTick - activeStep.value.startAt))
  }
  return formatSubDuration(activeStep.value.durationMs)
})

const finalReportText = computed(() => {
  const raw = activeStep.value?.result
  if (!raw) return ''
  if (outcome.value.state !== 'completed') return props.formatResultFn(raw)
  return String(raw).trim()
})

const resultBoxTitle = computed(() => ({
  completed: '返回给主会话的最终汇报',
  timeout: '超时终止说明',
  failed: '执行异常信息',
  interrupted: '中断状态说明'
}[outcome.value.state] || '返回结果'))

function toggleSubItem(id) {
  const k = `${props.activeKey}::${id}`
  openMap[k] = !openMap[k]
}

function isItemOpen(id) {
  return !!openMap[`${props.activeKey}::${id}`]
}

function isLiveSubThink(idx) {
  return outcome.value.state === 'running' && idx === subSteps.value.length - 1 && subSteps.value[idx]?.type === 'thinking'
}

function thinkDurationLabel(s) {
  if (s.durationMs != null) return `思考 持续了 ${formatSubDuration(s.durationMs)}`
  if (s.startAt && s.endAt) return `思考 持续了 ${formatSubDuration(Math.max(0, s.endAt - s.startAt))}`
  return '思考过程'
}

function subToolDur(s) {
  if (s.status === 'running' && s.startAt) return formatSubDuration(Math.max(0, props.nowTick - s.startAt))
  return formatSubDuration(s.durationMs)
}
</script>

<style scoped>
.sub-drawer {
  width: 500px;
  max-width: 48vw;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid #e5e7eb;
  background: #ffffff;
  box-shadow: -4px 0 18px rgba(15, 23, 42, 0.05);
  transition: width 0.2s ease;
  z-index: 15;
}
.sub-drawer.is-expanded {
  width: 760px;
  max-width: 68vw;
}
@media (max-width: 1100px) {
  .sub-drawer {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(520px, 92vw);
    max-width: 92vw;
    z-index: 30;
  }
}

.drawer-tabbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid #f1f5f9;
  background: #f8fafc;
  flex-shrink: 0;
}
.drawer-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
}
.drawer-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 8px;
  font-size: 12px;
  color: #64748b;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.drawer-tab:hover {
  background: #eef2f7;
  color: #334155;
}
.drawer-tab.active {
  background: #ffffff;
  color: #1e293b;
  font-weight: 600;
  border-color: #e2e8f0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
}
.tab-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  flex-shrink: 0;
}
.tab-dot.dot-running {
  background: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.22);
  animation: pulse-dot 1.4s infinite;
}
.tab-dot.dot-completed { background: #10b981; }
.tab-dot.dot-timeout { background: #f59e0b; }
.tab-dot.dot-failed { background: #ef4444; }
.tab-dot.dot-interrupted { background: #9ca3af; }

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

.drawer-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.drawer-icon-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;
}
.drawer-icon-btn:hover {
  background: #e2e8f0;
  color: #1e293b;
}

.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px 20px;
}

.drawer-model-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.model-line {
  flex: 1;
  height: 1px;
  background: #f1f5f9;
}
.model-text {
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
}

.drawer-prompt-card {
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  padding: 10px 12px;
  margin-bottom: 14px;
}
.prompt-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.prompt-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
}
.prompt-title {
  font-size: 11.5px;
  font-weight: 600;
  color: #475569;
}
.prompt-toggle {
  margin-left: auto;
  font-size: 11px;
  color: #2563eb;
  cursor: pointer;
}
.prompt-toggle:hover { text-decoration: underline; }
.prompt-body {
  font-size: 12.5px;
  line-height: 1.55;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.prompt-body.clamped {
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.stream-summary-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 2px;
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  text-align: left;
}
.stream-summary-head:hover { color: #334155; }
.stream-summary-sub { color: #94a3b8; font-weight: 400; }
.stream-summary-err { color: #ef4444; font-weight: 500; }

.stream-timeline {
  margin-top: 4px;
  padding-left: 10px;
  border-left: 2px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.stream-empty {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 4px;
  font-size: 12px;
  color: #94a3b8;
}

.tl-think-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 4px 6px;
  border-radius: 6px;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
}
.tl-think-head:hover { background: #f8fafc; color: #334155; }
.tl-think-icon { font-size: 12px; }
.tl-think-body {
  margin: 4px 0 4px 18px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  font-size: 12px;
  line-height: 1.55;
  color: #475569;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow-y: auto;
}

.tl-tool-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  transition: background-color 0.12s;
}
.tl-tool-row:hover { background: #f8fafc; }
.tl-tool-row.open { background: #eff6ff; }
.tl-tool-row.failed { background: #fef2f2; }
.tl-tool-status {
  width: 14px;
  display: inline-flex;
  justify-content: center;
  flex-shrink: 0;
  font-weight: 700;
}
.tl-tool-verb {
  font-size: 11px;
  color: #94a3b8;
  flex-shrink: 0;
}
.tl-tool-name {
  font-weight: 500;
  color: #1e293b;
  flex-shrink: 0;
}
.tl-tool-arg {
  font-size: 11px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.tl-tool-dur {
  font-size: 11px;
  color: #94a3b8;
  margin-left: auto;
  flex-shrink: 0;
}
.tl-tool-detail {
  margin: 4px 0 4px 20px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tl-detail-title {
  font-size: 10.5px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 3px;
}
.tl-detail-pre {
  margin: 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: #ffffff;
  border: 1px solid #f1f5f9;
  font-size: 11px;
  line-height: 1.5;
  color: #334155;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.tl-text {
  margin-top: 4px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
}
.tl-text-tag {
  font-size: 10.5px;
  font-weight: 600;
  color: #2563eb;
  margin-bottom: 4px;
}

.drawer-result-box {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #d1fae5;
  background: #ecfdf5;
}
.drawer-result-box.res-timeout {
  border-color: #fde68a;
  background: #fffbeb;
}
.drawer-result-box.res-failed {
  border-color: #fecaca;
  background: #fef2f2;
}
.drawer-result-box.res-interrupted {
  border-color: #e5e7eb;
  background: #f9fafb;
}
.result-box-head {
  display: flex;
  align-items: center;
  font-size: 11.5px;
  font-weight: 600;
  color: #065f46;
  margin-bottom: 6px;
}
.res-timeout .result-box-head { color: #b45309; }
.res-failed .result-box-head { color: #b91c1c; }
.res-interrupted .result-box-head { color: #4b5563; }
.result-box-body.clamped {
  max-height: 180px;
  overflow: hidden;
  mask-image: linear-gradient(180deg, #000 72%, transparent);
}
.result-box-pre {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  color: #374151;
  max-height: 200px;
  overflow: auto;
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 14px;
  border-top: 1px solid #f1f5f9;
  background: #f8fafc;
  font-size: 12px;
  color: #475569;
  flex-shrink: 0;
}
.footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.footer-right {
  font-size: 11.5px;
  color: #64748b;
  flex-shrink: 0;
}
</style>

<template>
  <div
    class="sub-agent-card"
    :class="[`state-${outcome.state}`, { 'is-selected': selected }]"
    @click="$emit('inspect', step)"
  >
    <!-- 顶部主行：状态点 + 专员图标/名 + 状态/耗时/步数徽标 + 查看详情 -->
    <div class="sub-card-head">
      <span class="sub-status-indicator" :class="`dot-${outcome.state}`" aria-hidden="true">
        <el-icon v-if="outcome.state === 'running'" :size="13" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="outcome.state === 'completed'" :size="13"><CircleCheckFilled /></el-icon>
        <el-icon v-else-if="outcome.state === 'timeout'" :size="13"><WarningFilled /></el-icon>
        <el-icon v-else-if="outcome.state === 'failed'" :size="13"><CircleCloseFilled /></el-icon>
        <el-icon v-else :size="13"><VideoPause /></el-icon>
      </span>

      <span class="sub-role-badge">{{ roleTag }}</span>
      <span class="sub-role-name">{{ displayName }}</span>

      <span class="sub-meta-pill" :class="`pill-${outcome.state}`">
        <span>{{ stateLabel }}</span>
        <template v-if="durationText">
          <span class="meta-sep">·</span>
          <span class="font-mono">{{ durationText }}</span>
        </template>
        <template v-if="stats.totalTools > 0">
          <span class="meta-sep">·</span>
          <span>内部 {{ stats.totalTools }} 步</span>
        </template>
      </span>

      <span
        v-if="outcome.state === 'completed' && outcome.retryCount > 0"
        class="sub-retry-tag"
        title="子专员内部曾遇到报错并自行修复"
      >
        含 {{ outcome.retryCount }} 次重试
      </span>

      <span class="sub-inspect-link">
        <span>查看详情</span>
        <el-icon :size="11"><ArrowRight /></el-icon>
      </span>
    </div>

    <!-- 任务简述（单行省略，完整指令在右侧抽屉里看） -->
    <div v-if="taskPreview" class="sub-card-task" :title="taskFull">
      <span class="sub-task-label">任务：</span>
      <span class="sub-task-text">{{ taskPreview }}</span>
    </div>

    <!-- 底部动态行：运行中显示最新执行动作；终态显示一句话结论/错误原因 -->
    <div class="sub-card-foot">
      <template v-if="outcome.state === 'running'">
        <span class="sub-live-arrow">↳</span>
        <template v-if="stats.runningTool">
          <el-icon :size="11" class="is-loading shrink-0 text-blue-500"><Loading /></el-icon>
          <span class="font-medium text-gray-700">正在执行 {{ toolLabelFn(stats.runningTool.toolName) }}</span>
          <span v-if="liveArgPreview" class="sub-live-arg font-mono">{{ liveArgPreview }}</span>
        </template>
        <template v-else-if="stats.lastStep?.type === 'thinking'">
          <el-icon :size="11" class="is-loading shrink-0 text-blue-500"><Loading /></el-icon>
          <span class="text-gray-600">正在深度思考</span>
          <span v-if="liveThinkTail" class="sub-live-arg">{{ liveThinkTail }}</span>
        </template>
        <template v-else-if="stats.lastStep?.type === 'text' && stats.lastStep?.content">
          <el-icon :size="11" class="is-loading shrink-0 text-blue-500"><Loading /></el-icon>
          <span class="text-gray-600">正在整理输出</span>
          <span class="sub-live-arg">{{ stats.lastStep.content.replace(/\s+/g, ' ').slice(-40) }}</span>
        </template>
        <template v-else-if="stats.lastTool">
          <el-icon :size="11" class="is-loading shrink-0 text-blue-500"><Loading /></el-icon>
          <span class="text-gray-600">已完成 {{ toolLabelFn(stats.lastTool.toolName) }}，继续推进中…</span>
        </template>
        <template v-else>
          <span class="text-gray-500">专员已接单，正在规划执行步骤…</span>
        </template>
        <span v-if="stats.totalTools > 0" class="sub-foot-stat">
          已查阅 {{ stats.readCount }} 项 · 已执行 {{ stats.execCount }} 步
        </span>
      </template>

      <template v-else>
        <span class="sub-summary-prefix">{{ summaryPrefix }}</span>
        <span class="sub-summary-text">{{ outcome.summary }}</span>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  ArrowRight, CircleCheckFilled, CircleCloseFilled, Loading, VideoPause, WarningFilled
} from '@element-plus/icons-vue'
import {
  detectSubAgentOutcome,
  extractSubTaskPrompt,
  formatSubDuration,
  subAgentDisplayName,
  subAgentRoleTag,
  subStepStats
} from '@/utils/agentNames'

const props = defineProps({
  step: { type: Object, required: true },
  nowTick: { type: Number, default: 0 },
  selected: { type: Boolean, default: false },
  toolLabelFn: { type: Function, default: (name) => name || 'unknown' },
  argPreviewFn: { type: Function, default: () => '' }
})

defineEmits(['inspect'])

const displayName = computed(() => subAgentDisplayName(props.step))
const roleTag = computed(() => subAgentRoleTag(props.step))
const stats = computed(() => subStepStats(props.step?.subSteps || []))
const outcome = computed(() => detectSubAgentOutcome(props.step))

const taskFull = computed(() => extractSubTaskPrompt(props.step?.arguments))
const taskPreview = computed(() => {
  const s = taskFull.value.replace(/\s+/g, ' ').trim()
  return s.length > 90 ? `${s.slice(0, 90)}…` : s
})

const stateLabel = computed(() => ({
  running: '运行中',
  completed: '已完成',
  timeout: '已超时',
  failed: '执行失败',
  interrupted: '已中断'
}[outcome.value.state] || '执行中'))

const durationText = computed(() => {
  if (outcome.value.state === 'running') {
    if (!props.step?.startAt) return ''
    return formatSubDuration(Math.max(0, props.nowTick - props.step.startAt))
  }
  return formatSubDuration(props.step?.durationMs)
})

const liveArgPreview = computed(() => {
  const t = stats.value.runningTool
  if (!t) return ''
  return props.argPreviewFn(t.arguments)
})

const liveThinkTail = computed(() => {
  const s = stats.value.lastStep
  if (!s || s.type !== 'thinking' || !s.content) return ''
  const clean = s.content.replace(/\s+/g, ' ').trim()
  return clean.length > 56 ? `…${clean.slice(-56)}` : clean
})

const summaryPrefix = computed(() => ({
  completed: '结论：',
  timeout: '原因：',
  failed: '原因：',
  interrupted: '状态：'
}[outcome.value.state] || ''))
</script>

<style scoped>
.sub-agent-card {
  margin: 6px 0;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}
.sub-agent-card:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
}
.sub-agent-card.is-selected {
  border-color: #3b82f6;
  background: #eff6ff;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.12);
}
.sub-agent-card.state-running {
  border-color: #bfdbfe;
  background: linear-gradient(180deg, #f8fbff 0%, #f3f7ff 100%);
}
.sub-agent-card.state-timeout {
  border-color: #fed7aa;
  background: #fffbeb;
}
.sub-agent-card.state-failed {
  border-color: #fecaca;
  background: #fef2f2;
}
.sub-agent-card.state-interrupted {
  border-color: #e5e7eb;
  background: #f9fafb;
  opacity: 0.92;
}

.sub-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.sub-status-indicator {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.dot-running { color: #2563eb; }
.dot-completed { color: #10b981; }
.dot-timeout { color: #d97706; }
.dot-failed { color: #ef4444; }
.dot-interrupted { color: #9ca3af; }

.sub-role-badge {
  font-size: 10px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
  flex-shrink: 0;
}
.state-timeout .sub-role-badge { background: #fef3c7; color: #b45309; }
.state-failed .sub-role-badge { background: #fee2e2; color: #b91c1c; }
.state-interrupted .sub-role-badge { background: #e5e7eb; color: #4b5563; }

.sub-role-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
}

.sub-meta-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  color: #4b5563;
}
.pill-running { border-color: #bfdbfe; color: #1d4ed8; background: #ffffff; }
.pill-completed { border-color: #d1fae5; color: #047857; background: #ecfdf5; }
.pill-timeout { border-color: #fde68a; color: #b45309; background: #fef3c7; }
.pill-failed { border-color: #fecaca; color: #b91c1c; background: #fee2e2; }
.pill-interrupted { border-color: #e5e7eb; color: #6b7280; background: #f3f4f6; }
.meta-sep { opacity: 0.45; }

.sub-retry-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #fef3c7;
  color: #b45309;
  font-weight: 500;
}

.sub-inspect-link {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 11px;
  font-weight: 500;
  color: #2563eb;
  flex-shrink: 0;
}
.sub-agent-card:hover .sub-inspect-link {
  text-decoration: underline;
}

.sub-card-task {
  margin-top: 5px;
  font-size: 12px;
  line-height: 1.45;
  color: #4b5563;
  display: flex;
  align-items: baseline;
  min-width: 0;
}
.sub-task-label {
  color: #9ca3af;
  flex-shrink: 0;
}
.sub-task-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub-card-foot {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed rgba(156, 163, 175, 0.35);
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  line-height: 1.4;
  color: #4b5563;
  min-width: 0;
}
.sub-live-arrow {
  color: #93c5fd;
  font-weight: 700;
  flex-shrink: 0;
}
.sub-live-arg {
  color: #9ca3af;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.sub-foot-stat {
  margin-left: auto;
  font-size: 11px;
  color: #9ca3af;
  flex-shrink: 0;
}
.sub-summary-prefix {
  color: #9ca3af;
  flex-shrink: 0;
}
.sub-summary-text {
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.state-timeout .sub-summary-text { color: #b45309; }
.state-failed .sub-summary-text { color: #b91c1c; }
</style>

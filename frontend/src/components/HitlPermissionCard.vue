<template>
  <div
    :id="`hitl-card-${card.decisionId}`"
    class="rounded-xl border transition-all duration-300"
    :class="cardWrapperClass"
  >
    <!-- 1. 待处理态（PENDING 且未本地超时） -->
    <template v-if="isPendingActive">
      <div class="flex items-center justify-between border-b border-amber-200/80 bg-amber-50/80 px-4 py-2.5 rounded-t-xl">
        <div class="flex items-center gap-2 text-sm font-semibold text-amber-900">
          <span class="flex h-5 w-5 items-center justify-center rounded-full bg-amber-500 text-xs text-white">!</span>
          <span v-if="isQueued">
            待审批队列 (当前第 {{ card.queueIndex }} 项 / 共 {{ card.queueTotal }} 项)
          </span>
          <span v-else>需要您的授权确认</span>
          <span
            v-if="card.agentCode && card.agentCode !== 'single' && card.agentCode !== 'router'"
            class="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-normal text-amber-700"
          >
            来自专员 {{ card.agentCode }}
          </span>
        </div>
        <span class="rounded-full bg-white/90 px-2.5 py-0.5 font-mono text-xs font-medium text-amber-700 shadow-xs">
          剩余 {{ formattedCountdown }}
        </span>
      </div>

      <!-- 二期 HITL：60s 超时降级为后台挂起时的温柔提示条（卡片依然可点击） -->
      <div
        v-if="card.parked"
        class="flex items-center gap-2 border-b border-blue-200/70 bg-blue-50/80 px-4 py-1.5 text-xs text-blue-800"
      >
        <span>⏸</span>
        <span>任务已转入后台等待您的审批（有效至 {{ card.expiresAt || '30分钟内' }}）</span>
      </div>

      <div class="space-y-2.5 px-4 py-3 text-[13px] text-gray-700">
        <div class="flex items-baseline gap-2">
          <span class="w-18 shrink-0 text-gray-400">动作类型</span>
          <span class="font-semibold text-gray-900">{{ card.actionLabel || card.toolName }}</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="w-18 shrink-0 text-gray-400">影响对象</span>
          <span class="font-medium text-gray-800">{{ card.targetSummary || card.actionSummary }}</span>
        </div>
        <div v-if="card.keyParams && card.keyParams.length" class="flex items-start gap-2">
          <span class="w-18 shrink-0 text-gray-400">关键参数</span>
          <ul class="min-w-0 flex-1 space-y-1">
            <li v-for="(kp, idx) in card.keyParams" :key="idx" class="flex items-baseline gap-1.5 text-xs">
              <span class="text-gray-400">• {{ kp.label }}：</span>
              <span class="break-all font-medium text-gray-800">{{ kp.value }}</span>
            </li>
          </ul>
        </div>

        <!-- 原始参数 JSON 折叠区 -->
        <div>
          <button
            type="button"
            class="inline-flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
            @click="showRaw = !showRaw"
          >
            <span>{{ showRaw ? '收起完整原始参数 JSON ▴' : '展开完整原始参数 JSON ▾' }}</span>
          </button>
          <pre
            v-if="showRaw"
            class="mt-1.5 max-h-44 overflow-auto rounded-lg bg-gray-900 p-2.5 font-mono text-[11px] leading-relaxed text-gray-100"
          >{{ formattedRawPayload }}</pre>
        </div>

        <!-- 二期 HITL：后续排队预览（只读） -->
        <div
          v-if="card.queuePreview && card.queuePreview.length"
          class="rounded-lg border border-amber-200/70 bg-amber-50/40 px-3 py-2 text-xs text-gray-600"
        >
          <div class="mb-1 font-medium text-amber-900">后续排队中（需逐项审批，只读预览）：</div>
          <ul class="space-y-0.5 pl-2">
            <li v-for="(item, idx) in card.queuePreview" :key="item.toolCallId || idx">
              {{ (card.queueIndex || 1) + idx + 1 }}. {{ item.label || item.toolName }}（等待中）
            </li>
          </ul>
        </div>

        <!-- 拒绝理由轻量输入框 -->
        <div v-if="rejectMode" class="rounded-lg border border-red-200 bg-red-50/50 p-2.5">
          <div class="mb-1.5 text-xs font-medium text-red-700">请填写拒绝理由（AI 将据此调整方案）：</div>
          <el-input
            v-model="rejectReason"
            type="textarea"
            :rows="2"
            placeholder="例如：附件还没传完，先别提"
            maxlength="300"
            show-word-limit
          />
          <div class="mt-2 flex justify-end gap-2">
            <el-button size="small" :disabled="submitting" @click="rejectMode = false">取消</el-button>
            <el-button
              size="small"
              type="danger"
              :loading="submitting && activeOption === 'REJECTED'"
              @click="submitDecision('REJECTED')"
            >
              确认拒绝
            </el-button>
          </div>
        </div>
      </div>

      <!-- 底部操作按钮区 -->
      <div class="flex flex-wrap items-center justify-between gap-2 border-t border-amber-200/60 bg-gray-50/70 px-4 py-2.5 rounded-b-xl">
        <div class="flex items-center gap-2">
          <el-button
            v-if="isQueued && !rejectMode"
            size="small"
            plain
            type="danger"
            :loading="submitting && activeOption === 'REJECT_ALL'"
            :disabled="submitting"
            @click="submitDecision('REJECT_ALL')"
          >
            全部拒绝
          </el-button>
          <el-button
            v-if="!rejectMode"
            size="small"
            plain
            type="danger"
            :disabled="submitting"
            @click="rejectMode = true"
          >
            {{ isQueued ? '拒绝当前项 ▾' : '拒绝 ▾' }}
          </el-button>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <el-tooltip
            v-if="card.allowSessionGrant"
            :content="card.scopeDesc || '本会话内同类操作自动放行'"
            placement="top"
          >
            <el-button
              size="small"
              plain
              type="primary"
              :loading="submitting && activeOption === 'ALLOW_SESSION'"
              :disabled="submitting"
              @click="submitDecision('ALLOW_SESSION')"
            >
              本会话同类放行
            </el-button>
          </el-tooltip>
          <el-button
            size="small"
            type="primary"
            :loading="submitting && activeOption === 'ALLOW_ONCE'"
            :disabled="submitting"
            @click="submitDecision('ALLOW_ONCE')"
          >
            {{ isQueued ? '批准当前项' : '仅本次允许' }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 2. 已消费/已批准留痕态 -->
    <div
      v-else-if="isApprovedOrConsumed"
      class="flex flex-wrap items-center justify-between gap-2 px-3.5 py-2 text-xs text-emerald-800"
    >
      <div class="flex items-center gap-2">
        <span>✅</span>
        <span>
          <strong>{{ card.answeredByName || '当前用户' }}</strong>
          于 {{ card.answeredAt || card.createdAt }} 批准「{{ card.actionLabel || card.toolName }}」
          （{{ card.decisionOption === 'ALLOW_SESSION' ? '本会话同类放行' : '仅本次' }}）
        </span>
      </div>
      <span v-if="card.status === 'CONSUMED'" class="rounded bg-emerald-100 px-1.5 py-0.5 text-[11px] text-emerald-700">
        已执行
      </span>
    </div>

    <!-- 3. 自动放行提示条（带一键撤销授权按钮） -->
    <div
      v-else-if="card.status === 'AUTO_APPROVED_BY_GRANT'"
      class="flex flex-wrap items-center justify-between gap-2 px-3.5 py-2 text-xs text-blue-800"
    >
      <div class="flex items-center gap-1.5">
        <span>⚡</span>
        <span>
          已依据您授予的会话权限自动放行「{{ card.actionSummary || card.actionLabel }}」
        </span>
      </div>
      <button
        v-if="card.grantId && !grantRevoked"
        type="button"
        class="rounded border border-blue-300 bg-white px-2 py-0.5 text-[11px] font-medium text-blue-700 hover:bg-blue-50"
        @click="onRevokeGrant"
      >
        撤销本会话授权
      </button>
      <span v-else-if="grantRevoked" class="text-[11px] text-gray-400">已撤销同类授权</span>
    </div>

    <!-- 4. 已拒绝留痕态 -->
    <div
      v-else-if="card.status === 'REJECTED'"
      class="flex items-center gap-2 px-3.5 py-2 text-xs text-red-800"
    >
      <span>❌</span>
      <span>
        <strong>{{ card.answeredByName || '当前用户' }}</strong>
        于 {{ card.answeredAt || card.createdAt }} 拒绝「{{ card.actionLabel || card.toolName }}」
        <template v-if="card.userWords">｜理由：{{ card.userWords }}</template>
      </span>
    </div>

    <!-- 5. 过期失效态 -->
    <div
      v-else-if="isExpired"
      class="flex items-center gap-2 px-3.5 py-2 text-xs text-gray-500"
    >
      <span>⌛</span>
      <span>该审批单（{{ card.actionLabel || card.toolName }}）已于 {{ card.expiresAt || '超时' }} 过期失效，如需继续请重新发起</span>
    </div>

    <!-- 6. 已被新消息取代态（TakeOver） -->
    <div
      v-else-if="card.status === 'SUPERSEDED'"
      class="flex items-center gap-2 px-3.5 py-2 text-xs text-gray-500"
    >
      <span>⏹️</span>
      <span>「{{ card.actionLabel || card.toolName }}」已被新对话取代（未执行）</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { decideHitlDecision, revokeHitlGrant } from '@/api/hitl'

const props = defineProps({
  card: { type: Object, required: true },
  highlighted: { type: Boolean, default: false }
})

const emit = defineEmits(['decided', 'expired', 'revoked'])

const showRaw = ref(false)
const rejectMode = ref(false)
const rejectReason = ref('')
const submitting = ref(false)
const activeOption = ref('')
const grantRevoked = ref(false)
const remaining = ref(Number(props.card.remainingSeconds || 0))
let timer = null

watch(
  () => props.card.remainingSeconds,
  (val) => {
    remaining.value = Number(val || 0)
  }
)

const isExpired = computed(
  () => props.card.status === 'EXPIRED' || (props.card.status === 'PENDING' && remaining.value <= 0)
)
const isPendingActive = computed(() => props.card.status === 'PENDING' && remaining.value > 0)
const isApprovedOrConsumed = computed(
  () => props.card.status === 'APPROVED' || props.card.status === 'CONSUMED'
)
const isQueued = computed(
  () => Number(props.card.queueTotal || 0) > 1
)

const cardWrapperClass = computed(() => {
  if (props.highlighted) {
    return 'border-blue-500 bg-white shadow-lg ring-2 ring-blue-300'
  }
  if (isPendingActive.value) {
    return 'border-amber-300 bg-white shadow-md'
  }
  if (isApprovedOrConsumed.value) {
    return 'border-emerald-200 bg-emerald-50/60'
  }
  if (props.card.status === 'AUTO_APPROVED_BY_GRANT') {
    return 'border-blue-200 bg-blue-50/60'
  }
  if (props.card.status === 'REJECTED') {
    return 'border-red-200 bg-red-50/60'
  }
  return 'border-gray-200 bg-gray-50 text-gray-500'
})

const formattedCountdown = computed(() => {
  const sec = Math.max(0, remaining.value)
  const m = String(Math.floor(sec / 60)).padStart(2, '0')
  const s = String(sec % 60).padStart(2, '0')
  return `${m}:${s}`
})

const formattedRawPayload = computed(() => {
  try {
    return JSON.stringify(props.card.rawPayload ?? {}, null, 2)
  } catch {
    return String(props.card.rawPayload || '')
  }
})

function startCountdown() {
  stopCountdown()
  if (props.card.status !== 'PENDING') return
  timer = setInterval(() => {
    if (remaining.value > 0) {
      remaining.value -= 1
      if (remaining.value === 0) {
        stopCountdown()
        emit('expired', props.card.decisionId)
      }
    } else {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(startCountdown)
onBeforeUnmount(stopCountdown)
watch(() => props.card.status, startCountdown)

function generateCommandId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return `cmd_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`
  }
  return `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

async function submitDecision(option) {
  if (submitting.value) return
  if (option === 'REJECTED' && !rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝理由')
    return
  }
  submitting.value = true
  activeOption.value = option
  try {
    const resp = await decideHitlDecision(props.card.decisionId, {
      option,
      revision: props.card.revision,
      commandId: generateCommandId(),
      userWords: option === 'REJECTED'
        ? rejectReason.value.trim()
        : option === 'REJECT_ALL'
          ? (rejectReason.value.trim() || '用户选择全部拒绝')
          : null
    })
    rejectMode.value = false
    emit('decided', resp)
  } catch (err) {
    const msg = err?.message || '操作失败'
    if (msg.includes('EXPIRED')) {
      remaining.value = 0
      emit('expired', props.card.decisionId)
    }
  } finally {
    submitting.value = false
    activeOption.value = ''
  }
}

async function onRevokeGrant() {
  try {
    await revokeHitlGrant(props.card.grantId)
    grantRevoked.value = true
    ElMessage.success('已撤销本会话同类授权，后续操作将重新弹窗确认')
    emit('revoked', props.card.grantId)
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

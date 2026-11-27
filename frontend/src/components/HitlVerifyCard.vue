<template>
  <div
    :id="`hitl-card-${card.decisionId}`"
    class="rounded-xl border transition-all duration-300"
    :class="wrapperClass"
  >
    <!-- 1. 待人工核对态（橙色警戒） -->
    <template v-if="isPendingActive">
      <div class="flex items-center justify-between gap-2 rounded-t-xl border-b border-orange-200/80 bg-orange-50/90 px-4 py-2.5">
        <div class="flex min-w-0 flex-1 items-center gap-2 text-sm font-semibold text-orange-900">
          <span class="shrink-0">🔍</span>
          <span class="truncate">操作结果未知，请人工核对（严禁自动重试）</span>
        </div>
        <span class="shrink-0 whitespace-nowrap rounded-full bg-white/90 px-2.5 py-0.5 font-mono text-xs font-medium text-orange-700 shadow-xs">
          剩余 {{ formattedCountdown }}
        </span>
      </div>

      <div
        v-if="card.parked"
        class="flex items-center gap-2 border-b border-blue-200/70 bg-blue-50/80 px-4 py-1.5 text-xs text-blue-800"
      >
        <span class="shrink-0">⏸</span>
        <span class="min-w-0 break-words">核对任务已转入后台挂起，请前往主平台核对后返回确认</span>
      </div>

      <div class="space-y-2.5 px-4 py-3 text-[13px] text-gray-700">
        <p class="break-words leading-relaxed text-gray-800">
          系统在调用「<strong>{{ actionLabel }}</strong>」时发生网络超时，无法确认主平台是否已受理。
          为避免重复提交，请前往主平台核对后告知结果：
        </p>

        <ul class="space-y-1.5 rounded-lg border border-orange-200/70 bg-orange-50/40 p-3 text-xs">
          <li class="flex flex-wrap items-center gap-2">
            <span class="shrink-0 text-gray-500">• 业务单号：</span>
            <span class="min-w-0 break-all font-mono font-semibold text-gray-900">{{ bizId }}</span>
            <button
              type="button"
              class="shrink-0 rounded border border-orange-300 bg-white px-1.5 py-0.5 text-[11px] text-orange-700 hover:bg-orange-50"
              @click="copyBizId"
            >
              复制
            </button>
            <a
              v-if="platformUrl"
              :href="platformUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex shrink-0 items-center gap-0.5 font-medium text-blue-600 hover:underline"
            >
              打开主平台查看 ↗
            </a>
          </li>
          <li class="flex items-baseline gap-2">
            <span class="shrink-0 text-gray-500">• 请求时间：</span>
            <span class="min-w-0 break-all font-mono text-gray-800">{{ requestTime }}</span>
          </li>
          <li class="flex items-baseline gap-2">
            <span class="shrink-0 text-gray-500">• 错误摘要：</span>
            <span class="min-w-0 break-all text-red-700">{{ errorSummary }}</span>
          </li>
        </ul>
      </div>

      <div class="flex flex-wrap items-center justify-end gap-2 rounded-b-xl border-t border-orange-200/60 bg-gray-50/80 px-4 py-2.5">
        <el-button
          size="small"
          plain
          :disabled="submitting"
          :loading="submitting && activeOption === 'ABORTED'"
          @click="submitVerify('ABORTED')"
        >
          放弃
        </el-button>
        <el-button
          size="small"
          type="warning"
          plain
          :disabled="submitting"
          :loading="submitting && activeOption === 'CONFIRMED_NOT_APPLIED'"
          @click="submitVerify('CONFIRMED_NOT_APPLIED')"
        >
          确认未生效，重新执行
        </el-button>
        <el-button
          size="small"
          type="primary"
          :disabled="submitting"
          :loading="submitting && activeOption === 'CONFIRMED_SUCCEEDED'"
          @click="submitVerify('CONFIRMED_SUCCEEDED')"
        >
          主平台已生效，标记成功
        </el-button>
      </div>
    </template>

    <!-- 2. 已核对留痕态 -->
    <div v-else class="flex items-start gap-2 px-3.5 py-2 text-xs" :class="resolvedTextClass">
      <span class="shrink-0">{{ resolvedIcon }}</span>
      <span class="min-w-0 flex-1 break-words">{{ resolvedSummary }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { decideHitlDecision } from '@/api/hitl'

const props = defineProps({
  card: { type: Object, required: true },
  highlighted: { type: Boolean, default: false }
})

const emit = defineEmits(['decided', 'expired'])

const submitting = ref(false)
const activeOption = ref('')
const remainSec = ref(Number(props.card.remainingSeconds || 0))
let timer = null

const raw = computed(() => props.card?.rawPayload || {})
const bizId = computed(() => raw.value.bizId || props.card.targetSummary || '—')
const actionLabel = computed(() => raw.value.actionLabel || props.card.actionLabel || props.card.toolName)
const requestTime = computed(() => raw.value.requestTime || props.card.createdAt || '—')
const errorSummary = computed(() => raw.value.errorSummary || '网络超时或响应中断')
const platformUrl = computed(() => raw.value.platformUrl || '')

const isExpired = computed(() => props.card.status === 'EXPIRED' || (props.card.status === 'PENDING' && remainSec.value <= 0))
const isPendingActive = computed(() => props.card.status === 'PENDING' && remainSec.value > 0)

const formattedCountdown = computed(() => {
  const s = Math.max(0, remainSec.value)
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
})

const wrapperClass = computed(() => {
  if (isPendingActive.value) {
    return props.highlighted
      ? 'border-orange-400 bg-white shadow-md ring-2 ring-orange-300/70'
      : 'border-orange-300 bg-white shadow-sm'
  }
  if (props.card.status === 'CONFIRMED_SUCCEEDED') return 'border-emerald-200 bg-emerald-50/50'
  if (props.card.status === 'CONFIRMED_NOT_APPLIED') return 'border-blue-200 bg-blue-50/50'
  return 'border-gray-200 bg-gray-50/70'
})

const resolvedIcon = computed(() => {
  if (props.card.status === 'CONFIRMED_SUCCEEDED') return '✅'
  if (props.card.status === 'CONFIRMED_NOT_APPLIED') return '🔄'
  if (isExpired.value) return '⌛'
  return '⏹️'
})

const resolvedTextClass = computed(() => {
  if (props.card.status === 'CONFIRMED_SUCCEEDED') return 'text-emerald-800'
  if (props.card.status === 'CONFIRMED_NOT_APPLIED') return 'text-blue-800'
  return 'text-gray-600'
})

const resolvedSummary = computed(() => {
  if (props.card.status === 'CONFIRMED_SUCCEEDED') {
    return `已人工核对主平台生效（${bizId.value}），补记成功审计（未重发请求）`
  }
  if (props.card.status === 'CONFIRMED_NOT_APPLIED') {
    return `已人工核对主平台未生效（${bizId.value}），已安全重发一次`
  }
  if (props.card.status === 'ABORTED') {
    return `已放弃「${actionLabel.value}」（${bizId.value}）`
  }
  if (isExpired.value) {
    return `核对单（${bizId.value}）已于 ${props.card.expiresAt || '超时'} 过期失效，如需继续请重新发起`
  }
  return `核对单已结束（${props.card.status}）`
})

function startTimer() {
  stopTimer()
  if (props.card.status !== 'PENDING') return
  timer = setInterval(() => {
    if (remainSec.value > 0) {
      remainSec.value -= 1
      if (remainSec.value === 0) {
        stopTimer()
        emit('expired', props.card.decisionId)
      }
    } else {
      stopTimer()
    }
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

watch(() => props.card.remainingSeconds, (v) => {
  remainSec.value = Number(v || 0)
})

watch(() => props.card.status, () => {
  startTimer()
})

onMounted(startTimer)
onBeforeUnmount(stopTimer)

async function copyBizId() {
  try {
    await navigator.clipboard.writeText(String(bizId.value))
    ElMessage.success('已复制业务单号')
  } catch {
    ElMessage.info(`业务单号：${bizId.value}`)
  }
}

async function submitVerify(option) {
  if (submitting.value || !isPendingActive.value) return
  submitting.value = true
  activeOption.value = option
  try {
    const commandId = `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
    const resp = await decideHitlDecision(props.card.decisionId, {
      option,
      revision: props.card.revision,
      commandId
    })
    emit('decided', resp)
  } catch (err) {
    const msg = err?.response?.data?.msg || err?.message || '操作失败，请重试'
    if (msg.includes('EXPIRED')) {
      remainSec.value = 0
      emit('expired', props.card.decisionId)
    }
    ElMessage.error(msg)
  } finally {
    submitting.value = false
    activeOption.value = ''
  }
}
</script>

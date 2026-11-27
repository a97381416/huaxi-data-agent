<template>
  <div
    :id="`hitl-card-${card.decisionId}`"
    class="rounded-xl border transition-all duration-300"
    :class="wrapperClass"
  >
    <!-- 1. 待拍板态 -->
    <template v-if="card.status === 'PENDING'">
      <div class="flex items-center justify-between rounded-t-xl border-b border-indigo-200/80 bg-indigo-50/80 px-4 py-2.5">
        <div class="flex items-center gap-2 text-sm font-semibold text-indigo-900">
          <span>📋</span>
          <span>执行计划确认</span>
          <span v-if="planTitle" class="text-xs font-normal text-indigo-700">· {{ planTitle }}</span>
        </div>
        <span class="rounded-full bg-white/90 px-2.5 py-0.5 font-mono text-xs font-medium text-indigo-700 shadow-xs">
          剩余 {{ formattedCountdown }}
        </span>
      </div>

      <div
        v-if="card.parked"
        class="flex items-center gap-2 border-b border-blue-200/70 bg-blue-50/80 px-4 py-1.5 text-xs text-blue-800"
      >
        <span>⏸</span>
        <span>计划确认已转入后台挂起（有效至 {{ card.expiresAt || '30分钟内' }}）</span>
      </div>

      <div class="space-y-2.5 px-4 py-3 text-[13px] text-gray-800">
        <div
          class="max-h-64 overflow-auto rounded-lg border border-indigo-100 bg-indigo-50/20 p-3 text-[13px] leading-relaxed whitespace-pre-wrap"
        >{{ planMarkdown }}</div>

        <!-- 打回修改意见输入框 -->
        <div v-if="reviseMode" class="rounded-lg border border-amber-200 bg-amber-50/60 p-2.5">
          <div class="mb-1.5 text-xs font-medium text-amber-800">请填写计划修改意见（AI 将据此调整后重新确认）：</div>
          <el-input
            v-model="reviseWords"
            type="textarea"
            :rows="2"
            placeholder="例如：去掉项目 C，其余照做"
            maxlength="400"
            show-word-limit
          />
          <div class="mt-2 flex justify-end gap-2">
            <el-button size="small" :disabled="submitting" @click="reviseMode = false">取消</el-button>
            <el-button
              size="small"
              type="warning"
              :loading="submitting && activeOption === 'REVISE_PLAN'"
              @click="submitPlan('REVISE_PLAN')"
            >
              确认打回修改
            </el-button>
          </div>
        </div>
      </div>

      <div class="flex flex-wrap items-center justify-end gap-2 rounded-b-xl border-t border-indigo-200/60 bg-gray-50/80 px-4 py-2.5">
        <el-button
          v-if="!reviseMode"
          size="small"
          plain
          type="warning"
          :disabled="submitting"
          @click="reviseMode = true"
        >
          打回修改（附意见）
        </el-button>
        <el-button
          size="small"
          type="primary"
          :disabled="submitting"
          :loading="submitting && activeOption === 'APPROVE_PLAN'"
          @click="submitPlan('APPROVE_PLAN')"
        >
          批准并开始执行
        </el-button>
      </div>
    </template>

    <!-- 2. 已处理留痕态 -->
    <div v-else class="flex items-center gap-2 px-3.5 py-2 text-xs" :class="resolvedClass">
      <span>{{ card.status === 'APPROVED' ? '✅' : card.status === 'REVISED' ? '🔄' : '⏹️' }}</span>
      <span v-if="card.status === 'APPROVED'">已批准执行计划「{{ planTitle }}」</span>
      <span v-else-if="card.status === 'REVISED'">
        已打回执行计划「{{ planTitle }}」<template v-if="card.userWords">｜修改意见：{{ card.userWords }}</template>
      </span>
      <span v-else>执行计划确认单已结束（{{ card.status }}）</span>
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

const reviseMode = ref(false)
const reviseWords = ref('')
const submitting = ref(false)
const activeOption = ref('')
const remainSec = ref(Number(props.card.remainingSeconds || 0))
let timer = null

const raw = computed(() => props.card?.rawPayload || {})
const planTitle = computed(() => raw.value.title || props.card.targetSummary || props.card.actionSummary || '任务执行计划')
const planMarkdown = computed(() => raw.value.planMarkdown || props.card.actionSummary || '')

const formattedCountdown = computed(() => {
  const s = Math.max(0, remainSec.value)
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
})

const wrapperClass = computed(() => {
  if (props.card.status === 'PENDING') {
    return props.highlighted
      ? 'border-indigo-400 bg-white shadow-md ring-2 ring-indigo-300/70'
      : 'border-indigo-300 bg-white shadow-sm'
  }
  if (props.card.status === 'APPROVED') return 'border-emerald-200 bg-emerald-50/50'
  if (props.card.status === 'REVISED') return 'border-amber-200 bg-amber-50/50'
  return 'border-gray-200 bg-gray-50/70'
})

const resolvedClass = computed(() => {
  if (props.card.status === 'APPROVED') return 'text-emerald-800'
  if (props.card.status === 'REVISED') return 'text-amber-800'
  return 'text-gray-500'
})

function startTimer() {
  stopTimer()
  if (props.card.status !== 'PENDING') return
  timer = setInterval(() => {
    if (remainSec.value > 0) {
      remainSec.value -= 1
    } else {
      stopTimer()
      emit('expired', props.card.decisionId)
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

watch(() => props.card.status, (st) => {
  if (st !== 'PENDING') stopTimer()
})

onMounted(startTimer)
onBeforeUnmount(stopTimer)

async function submitPlan(option) {
  if (submitting.value) return
  if (option === 'REVISE_PLAN' && !reviseWords.value.trim()) {
    ElMessage.warning('请填写修改意见')
    return
  }
  submitting.value = true
  activeOption.value = option
  try {
    const commandId = `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
    const resp = await decideHitlDecision(props.card.decisionId, {
      option,
      revision: props.card.revision,
      commandId,
      userWords: option === 'REVISE_PLAN' ? reviseWords.value.trim() : null
    })
    reviseMode.value = false
    emit('decided', resp)
  } catch (err) {
    const msg = err?.response?.data?.msg || err?.message || '操作失败，请重试'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
    activeOption.value = ''
  }
}
</script>

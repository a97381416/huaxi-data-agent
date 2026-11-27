<template>
  <div
    :id="`hitl-card-${card.decisionId}`"
    class="rounded-xl border transition-all duration-300"
    :class="wrapperClass"
    @keydown.enter="onEnterKey"
  >
    <!-- 1. 待处理态（步骤问答卡） -->
    <template v-if="isPendingActive">
      <div class="rounded-t-xl border-b border-blue-200/80 bg-blue-50/80 px-4 py-2.5">
        <div class="flex items-center justify-between gap-2">
          <div class="flex min-w-0 flex-1 items-center gap-2 text-sm font-semibold text-blue-900">
            <span class="shrink-0">{{ isRequired ? '📋' : '💬' }}</span>
            <span class="truncate">{{ headerTitle }}</span>
          </div>
          <div class="flex shrink-0 items-center gap-2 whitespace-nowrap">
            <span
              v-if="isRequired"
              class="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-800"
            >
              必选
            </span>
            <span v-if="isRequired && hasExpiryTracking" class="text-[11px] font-mono text-blue-700">
              剩余 {{ formattedCountdown }}
            </span>
            <span v-else-if="!isRequired && softWaitSec > 0" class="text-[11px] font-mono text-blue-600">
              {{ softWaitSec }}s 后自动跳过
            </span>
            <span class="rounded-full bg-white/90 px-2.5 py-0.5 text-xs font-medium text-blue-700 shadow-xs">
              {{ questions.length ? `步骤 ${currentStep + 1} / ${questions.length}` : '0 题' }}
            </span>
          </div>
        </div>
        <!-- 进度条 -->
        <div class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-blue-100">
          <div
            class="h-full rounded-full bg-blue-500 transition-all duration-300"
            :style="{ width: `${questions.length ? progressPercent : 0}%` }"
          />
        </div>
      </div>

      <!-- 必答态超 60s 转后台挂起提示条（卡片保持可操作） -->
      <div
        v-if="isRequired && card.parked"
        class="flex items-center gap-2 border-b border-blue-200/70 bg-blue-50/80 px-4 py-1.5 text-xs text-blue-800"
      >
        <span class="shrink-0">⏸</span>
        <span class="min-w-0 break-words">选择确认已转入后台等待（有效至 {{ card.expiresAt || '30分钟内' }}），可随时完成选择后继续</span>
      </div>

      <div v-if="!questions.length" class="px-4 py-5 text-center text-xs text-gray-500">
        {{ isRequired ? '暂无待确认问题项。' : '暂无需要补充的细节问题，您可以直接跳过让 AI 继续执行。' }}
      </div>

      <div v-else-if="currentQ" class="space-y-3 px-4 py-3.5 text-[13px] text-gray-800">
        <div class="flex items-start gap-2 font-medium text-gray-900">
          <span class="min-w-0 flex-1 break-words">Q{{ currentStep + 1 }}. {{ currentQ.title }}</span>
          <span class="mt-0.5 shrink-0 whitespace-nowrap rounded bg-blue-50 px-1.5 py-0.5 text-[11px] font-normal text-blue-600">
            {{ typeLabel(currentQ.type) }}
          </span>
        </div>

        <!-- 选项搜索框（候选项 > 6 时自动显示） -->
        <div
          v-if="(currentQ.type === 'single' || currentQ.type === 'multiple') && (currentQ.options || []).length > 6"
          class="relative"
        >
          <input
            v-model="searchKeyword"
            type="text"
            placeholder="🔍 输入关键词快速筛选选项..."
            class="w-full rounded-lg border border-gray-200 bg-gray-50/70 px-3 py-1.5 pr-7 text-xs text-gray-800 outline-none transition-colors focus:border-blue-400 focus:bg-white"
          />
          <button
            v-if="searchKeyword"
            type="button"
            class="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-gray-400 hover:text-gray-600"
            @click="searchKeyword = ''"
          >
            ✕
          </button>
        </div>

        <!-- 单选 -->
        <div v-if="currentQ.type === 'single'" class="space-y-2">
          <div class="max-h-64 space-y-2 overflow-y-auto pr-0.5">
            <label
              v-for="opt in filteredOptions"
              :key="opt"
              class="flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 transition-colors"
              :class="singleSelected(currentQ.id) === opt ? 'border-blue-400 bg-blue-50/70 text-blue-900' : 'border-gray-200 hover:bg-gray-50'"
            >
              <input
                type="radio"
                :name="`q-${card.decisionId}-${currentQ.id}`"
                :value="opt"
                :checked="singleSelected(currentQ.id) === opt"
                class="mt-0.5 shrink-0 accent-blue-600"
                @change="setSingle(currentQ.id, opt)"
              />
              <div class="min-w-0 flex-1">
                <div class="break-words">{{ opt }}</div>
                <div
                  v-if="optionDesc(currentQ, opt)"
                  class="mt-0.5 break-words text-[11px] leading-snug text-gray-500"
                >
                  {{ optionDesc(currentQ, opt) }}
                </div>
              </div>
            </label>
            <div
              v-if="searchKeyword.trim() && !filteredOptions.length"
              class="rounded-lg border border-dashed border-gray-200 py-3 text-center text-xs text-gray-400"
            >
              未找到匹配「{{ searchKeyword.trim() }}」的选项
            </div>
          </div>
          <div
            v-if="currentQ.allowCustom !== false"
            class="flex items-center gap-2 rounded-lg border px-3 py-1.5 transition-colors"
            :class="singleSelected(currentQ.id) === '__CUSTOM__' ? 'border-blue-400 bg-blue-50/70' : 'border-gray-200'"
          >
            <input
              type="radio"
              :name="`q-${card.decisionId}-${currentQ.id}`"
              value="__CUSTOM__"
              :checked="singleSelected(currentQ.id) === '__CUSTOM__'"
              class="accent-blue-600"
              @change="setSingle(currentQ.id, '__CUSTOM__')"
            />
            <span class="shrink-0 text-gray-600">其他：</span>
            <input
              v-model="customInputs[currentQ.id]"
              type="text"
              placeholder="请输入自定义说明..."
              class="min-w-0 flex-1 border-0 bg-transparent text-[13px] outline-none focus:outline-none"
              @focus="setSingle(currentQ.id, '__CUSTOM__')"
            />
          </div>
        </div>

        <!-- 多选 -->
        <div v-else-if="currentQ.type === 'multiple'" class="space-y-2">
          <div class="max-h-64 space-y-2 overflow-y-auto pr-0.5">
            <label
              v-for="opt in filteredOptions"
              :key="opt"
              class="flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 transition-colors"
              :class="isMultiChecked(currentQ.id, opt) ? 'border-blue-400 bg-blue-50/70 text-blue-900' : 'border-gray-200 hover:bg-gray-50'"
            >
              <input
                type="checkbox"
                :checked="isMultiChecked(currentQ.id, opt)"
                class="mt-0.5 shrink-0 accent-blue-600"
                @change="toggleMulti(currentQ.id, opt)"
              />
              <div class="min-w-0 flex-1">
                <div class="break-words">{{ opt }}</div>
                <div
                  v-if="optionDesc(currentQ, opt)"
                  class="mt-0.5 break-words text-[11px] leading-snug text-gray-500"
                >
                  {{ optionDesc(currentQ, opt) }}
                </div>
              </div>
            </label>
            <div
              v-if="searchKeyword.trim() && !filteredOptions.length"
              class="rounded-lg border border-dashed border-gray-200 py-3 text-center text-xs text-gray-400"
            >
              未找到匹配「{{ searchKeyword.trim() }}」的选项
            </div>
          </div>
          <div
            v-if="currentQ.allowCustom !== false"
            class="flex items-center gap-2 rounded-lg border px-3 py-1.5 transition-colors"
            :class="isMultiChecked(currentQ.id, '__CUSTOM__') ? 'border-blue-400 bg-blue-50/70' : 'border-gray-200'"
          >
            <input
              type="checkbox"
              :checked="isMultiChecked(currentQ.id, '__CUSTOM__')"
              class="accent-blue-600"
              @change="toggleMulti(currentQ.id, '__CUSTOM__')"
            />
            <span class="shrink-0 text-gray-600">其他：</span>
            <input
              v-model="customInputs[currentQ.id]"
              type="text"
              placeholder="请输入自定义补充..."
              class="min-w-0 flex-1 border-0 bg-transparent text-[13px] outline-none focus:outline-none"
              @focus="ensureMultiChecked(currentQ.id, '__CUSTOM__')"
            />
          </div>
          <div v-if="(multiMap[currentQ.id] || []).length" class="text-[11px] text-blue-600">
            已选 {{ (multiMap[currentQ.id] || []).length }} 项
          </div>
        </div>

        <!-- 文本填空 -->
        <div v-else>
          <el-input
            v-model="textAnswers[currentQ.id]"
            type="textarea"
            :rows="2"
            placeholder="请输入您的补充说明..."
            maxlength="400"
            show-word-limit
          />
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="flex flex-wrap items-center justify-between gap-2 rounded-b-xl border-t border-blue-200/60 bg-gray-50/80 px-4 py-2.5">
        <div class="flex min-w-0 flex-1 items-center gap-2">
          <el-button
            v-if="!isRequired"
            size="small"
            plain
            :disabled="submitting"
            :loading="submitting && activeOption === 'SKIP_QUESTION'"
            @click="submitSkip"
          >
            跳过，让AI自行决定
          </el-button>
          <span v-else-if="footerHintText" class="truncate text-[11px] text-gray-500" :title="footerHintText">
            💡 {{ footerHintText }}
          </span>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <el-button
            v-if="currentStep > 0"
            size="small"
            :disabled="submitting"
            @click="currentStep--"
          >
            ← 上一题
          </el-button>
          <el-button
            v-if="currentStep < questions.length - 1"
            size="small"
            type="primary"
            :disabled="submitting"
            @click="currentStep++"
          >
            下一题 →
          </el-button>
          <el-button
            v-else
            size="small"
            type="primary"
            :loading="submitting && activeOption === 'SUBMIT_ANSWERS'"
            :disabled="submitting || !questions.length"
            @click="submitAnswers"
          >
            {{ submitButtonLabel }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 2. 已作答留痕条 -->
    <div v-else-if="card.status === 'ANSWERED'" class="px-3.5 py-2 text-xs text-blue-900">
      <div class="flex items-center justify-between gap-2">
        <div class="flex min-w-0 flex-1 items-center gap-2">
          <span class="shrink-0">{{ isRequired ? '📋' : '💬' }}</span>
          <span v-if="isRequired && questions.length === 1" class="min-w-0 truncate">
            已确认选择：{{ formatSavedAnswer(questions[0].id) }}
          </span>
          <span v-else-if="isRequired" class="min-w-0 truncate">
            已完成 {{ questions.length }} 项选择确认
          </span>
          <span v-else class="min-w-0 truncate">已补充 {{ questions.length }} 项需求细节</span>
        </div>
        <button
          type="button"
          class="shrink-0 text-blue-600 hover:underline"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起详情 ▴' : '展开查看 ▾' }}
        </button>
      </div>
      <div v-if="expanded" class="mt-2 space-y-1 rounded-lg bg-white/80 p-2.5 text-gray-700">
        <div v-for="(q, idx) in questions" :key="q.id || idx" class="flex items-start gap-2">
          <span class="shrink-0 font-medium text-gray-500">Q{{ idx + 1 }}:</span>
          <span class="min-w-0 flex-1 break-words">{{ formatSavedAnswer(q.id) }}</span>
        </div>
      </div>
    </div>

    <!-- 3. 跳过 / 软超时 / 过期 / 失效 -->
    <div v-else class="flex items-center gap-2 px-3.5 py-2 text-xs text-gray-500">
      <span>{{ isExpired ? '⌛' : isSuperseded ? '⏹️' : '⏭️' }}</span>
      <span v-if="isExpired">该选择确认已过期，如需继续请重新发起</span>
      <span v-else-if="isSuperseded">该选择确认已被新对话取代</span>
      <span v-else-if="card.status === 'TIMED_OUT_SOFT'">问答等待超时（60s），已自动跳过并由 AI 按默认假设继续</span>
      <span v-else-if="card.status === 'SKIPPED'">已跳过需求补充，AI 将按默认假设继续</span>
      <span v-else>该需求澄清提问已结束（{{ card.status }}）</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { decideHitlDecision } from '@/api/hitl'

const props = defineProps({
  card: { type: Object, required: true },
  highlighted: { type: Boolean, default: false }
})

const emit = defineEmits(['decided', 'expired'])

const currentStep = ref(0)
const submitting = ref(false)
const activeOption = ref('')
const expanded = ref(false)
const searchKeyword = ref('')
const softWaitSec = ref(60)
const remainSec = ref(0)
let timer = null

const singleMap = reactive({})
const multiMap = reactive({})
const customInputs = reactive({})
const textAnswers = reactive({})

const isRequired = computed(() => Boolean(props.card?.rawPayload?.required))
const contextHint = computed(() => String(props.card?.rawPayload?.contextHint || '').trim())
const footerTip = computed(() => String(props.card?.rawPayload?.footerTip || '').trim())
const hasExpiryTracking = computed(() => props.card?.remainingSeconds != null)

const isExpired = computed(() => {
  if (props.card.status === 'EXPIRED') return true
  return isRequired.value && props.card.status === 'PENDING' && hasExpiryTracking.value && remainSec.value <= 0
})
const isSuperseded = computed(() => props.card.status === 'SUPERSEDED')
const isPendingActive = computed(() => props.card.status === 'PENDING' && !isExpired.value)

const headerTitle = computed(() => {
  if (isRequired.value) return contextHint.value || '需要您做出选择确认'
  return 'AI 需要向您确认几个细节'
})

const footerHintText = computed(() => {
  if (footerTip.value) return footerTip.value
  return ''
})

const submitButtonLabel = computed(() => {
  if (!isRequired.value) return '提交回答'
  return questions.value.length <= 1 ? '确认选择' : '提交确认'
})

const formattedCountdown = computed(() => {
  const s = Math.max(0, remainSec.value)
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
})

function resetLocalState() {
  currentStep.value = 0
  expanded.value = false
  searchKeyword.value = ''
  Object.keys(singleMap).forEach((k) => delete singleMap[k])
  Object.keys(multiMap).forEach((k) => delete multiMap[k])
  Object.keys(customInputs).forEach((k) => delete customInputs[k])
  Object.keys(textAnswers).forEach((k) => delete textAnswers[k])
  softWaitSec.value = Math.min(60, Math.max(0, Number(props.card?.remainingSeconds ?? 60)))
  remainSec.value = Math.max(0, Number(props.card?.remainingSeconds ?? 0))
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startTimer() {
  stopTimer()
  if (props.card.status !== 'PENDING') return
  timer = setInterval(() => {
    if (isRequired.value) {
      if (!hasExpiryTracking.value) return
      if (remainSec.value > 0) {
        remainSec.value -= 1
        if (remainSec.value === 0) {
          stopTimer()
          emit('expired', props.card.decisionId)
        }
      } else {
        stopTimer()
      }
    } else if (softWaitSec.value > 0) {
      softWaitSec.value -= 1
    } else {
      stopTimer()
    }
  }, 1000)
}

watch(() => props.card.decisionId, () => {
  resetLocalState()
  startTimer()
})

watch(() => props.card.remainingSeconds, (v) => {
  if (v != null) {
    remainSec.value = Math.max(0, Number(v))
  }
})

watch(() => props.card.status, (st) => {
  if (st !== 'PENDING') {
    stopTimer()
  } else {
    startTimer()
  }
})

watch(currentStep, () => {
  searchKeyword.value = ''
})

onMounted(() => {
  resetLocalState()
  startTimer()
})

onBeforeUnmount(stopTimer)

const questions = computed(() => {
  const raw = props.card?.rawPayload
  if (raw && Array.isArray(raw.questions)) return raw.questions
  return []
})

const currentQ = computed(() => questions.value[currentStep.value] || null)

const filteredOptions = computed(() => {
  const opts = currentQ.value?.options || []
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return opts
  const descs = currentQ.value?.optionDescriptions || {}
  return opts.filter((opt) => {
    const desc = String(descs[opt] || '').toLowerCase()
    return String(opt).toLowerCase().includes(kw) || desc.includes(kw)
  })
})

function optionDesc(q, opt) {
  const descs = q?.optionDescriptions
  if (!descs || typeof descs !== 'object') return ''
  return descs[opt] || ''
}

const progressPercent = computed(() => {
  if (!questions.value.length) return 100
  return Math.round(((currentStep.value + 1) / questions.value.length) * 100)
})

const wrapperClass = computed(() => {
  if (isPendingActive.value) {
    return props.highlighted
      ? 'border-blue-400 bg-white shadow-md ring-2 ring-blue-300/70'
      : 'border-blue-300 bg-white shadow-sm'
  }
  if (props.card.status === 'ANSWERED') {
    return 'border-blue-200 bg-blue-50/50'
  }
  return 'border-gray-200 bg-gray-50/70'
})

function typeLabel(t) {
  if (t === 'multiple') return '多选'
  if (t === 'text') return '填空'
  return '单选'
}

function singleSelected(qid) {
  return singleMap[qid] || ''
}

function setSingle(qid, val) {
  singleMap[qid] = val
}

function isMultiChecked(qid, val) {
  const arr = multiMap[qid] || []
  return arr.includes(val)
}

function ensureMultiChecked(qid, val) {
  const arr = multiMap[qid] || []
  if (!arr.includes(val)) multiMap[qid] = [...arr, val]
}

function toggleMulti(qid, val) {
  const arr = multiMap[qid] || []
  if (arr.includes(val)) {
    multiMap[qid] = arr.filter((x) => x !== val)
  } else {
    multiMap[qid] = [...arr, val]
  }
}

function buildAnswersPayload() {
  const out = {}
  for (const q of questions.value) {
    const qid = q.id
    if (q.type === 'text') {
      out[qid] = (textAnswers[qid] || '').trim()
    } else if (q.type === 'multiple') {
      const arr = [...(multiMap[qid] || [])]
      const idx = arr.indexOf('__CUSTOM__')
      if (idx >= 0) {
        const custom = (customInputs[qid] || '').trim()
        arr.splice(idx, 1, ...(custom ? [custom] : []))
      }
      out[qid] = arr
    } else {
      const sel = singleMap[qid] || ''
      out[qid] = sel === '__CUSTOM__' ? (customInputs[qid] || '').trim() : sel
    }
  }
  return out
}

function formatSavedAnswer(qid) {
  const ans = props.card?.answerJson
  if (!ans || typeof ans !== 'object') return '—'
  const v = ans[qid]
  if (Array.isArray(v)) return v.length ? v.join('、') : '未选择'
  return v || '未填写'
}

function onEnterKey(e) {
  if (!isPendingActive.value || submitting.value) return
  if (e.target && e.target.tagName === 'TEXTAREA') return
  e.preventDefault()
  if (currentStep.value < questions.value.length - 1) {
    currentStep.value++
  } else {
    submitAnswers()
  }
}

async function submitAnswers() {
  if (!questions.value.length || !isPendingActive.value) return
  for (let i = 0; i < questions.value.length; i++) {
    const q = questions.value[i]
    const qid = q.id
    if (q.type === 'single' && singleMap[qid] === '__CUSTOM__' && !(customInputs[qid] || '').trim()) {
      currentStep.value = i
      ElMessage.warning(`请填写第 ${i + 1} 题的自定义内容`)
      return
    }
    if (q.type === 'multiple' && isMultiChecked(qid, '__CUSTOM__') && !(customInputs[qid] || '').trim()) {
      currentStep.value = i
      ElMessage.warning(`请填写第 ${i + 1} 题的自定义内容`)
      return
    }
  }
  const answers = buildAnswersPayload()
  const hasAny = Object.values(answers).some((v) => (Array.isArray(v) ? v.length > 0 : Boolean(v)))
  if (!hasAny) {
    ElMessage.warning(isRequired.value ? '请至少选择或填写一项内容' : '请至少填写一题回答，或点击「跳过，让AI自行决定」')
    return
  }
  await sendDecide('SUBMIT_ANSWERS', answers)
}

async function submitSkip() {
  if (isRequired.value) return
  await sendDecide('SKIP_QUESTION', null)
}

async function sendDecide(option, answerJson) {
  if (submitting.value || !isPendingActive.value) return
  submitting.value = true
  activeOption.value = option
  try {
    const commandId = `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
    const resp = await decideHitlDecision(props.card.decisionId, {
      option,
      revision: props.card.revision,
      commandId,
      answerJson
    })
    emit('decided', resp)
  } catch (err) {
    const msg = err?.response?.data?.msg || err?.message || '提交失败，请重试'
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

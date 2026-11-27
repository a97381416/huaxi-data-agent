<template>
  <div
    :id="`hitl-card-${card.decisionId}`"
    class="rounded-xl border transition-all duration-300"
    :class="wrapperClass"
    @keydown.enter="onEnterKey"
  >
    <!-- 1. 待处理态（步骤问答卡） -->
    <template v-if="card.status === 'PENDING'">
      <div class="rounded-t-xl border-b border-blue-200/80 bg-blue-50/80 px-4 py-2.5">
        <div class="flex items-center justify-between gap-2">
          <div class="flex items-center gap-2 text-sm font-semibold text-blue-900">
            <span>💬</span>
            <span>AI 需要向您确认几个细节</span>
          </div>
          <span class="rounded-full bg-white/90 px-2.5 py-0.5 text-xs font-medium text-blue-700 shadow-xs">
            步骤 {{ currentStep + 1 }} / {{ questions.length }}
          </span>
        </div>
        <!-- 进度条 -->
        <div class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-blue-100">
          <div
            class="h-full rounded-full bg-blue-500 transition-all duration-300"
            :style="{ width: `${progressPercent}%` }"
          />
        </div>
      </div>

      <div v-if="currentQ" class="space-y-3 px-4 py-3.5 text-[13px] text-gray-800">
        <div class="flex items-baseline gap-2 font-medium text-gray-900">
          <span>Q{{ currentStep + 1 }}. {{ currentQ.title }}</span>
          <span class="shrink-0 rounded bg-blue-50 px-1.5 py-0.5 text-[11px] font-normal text-blue-600">
            {{ typeLabel(currentQ.type) }}
          </span>
        </div>

        <!-- 单选 -->
        <div v-if="currentQ.type === 'single'" class="space-y-2">
          <label
            v-for="opt in currentQ.options || []"
            :key="opt"
            class="flex cursor-pointer items-center gap-2.5 rounded-lg border px-3 py-2 transition-colors"
            :class="singleSelected(currentQ.id) === opt ? 'border-blue-400 bg-blue-50/70 text-blue-900' : 'border-gray-200 hover:bg-gray-50'"
          >
            <input
              type="radio"
              :name="`q-${card.decisionId}-${currentQ.id}`"
              :value="opt"
              :checked="singleSelected(currentQ.id) === opt"
              class="accent-blue-600"
              @change="setSingle(currentQ.id, opt)"
            />
            <span>{{ opt }}</span>
          </label>
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
              class="min-w-0 flex-1 bg-transparent text-[13px] outline-none"
              @focus="setSingle(currentQ.id, '__CUSTOM__')"
            />
          </div>
        </div>

        <!-- 多选 -->
        <div v-else-if="currentQ.type === 'multiple'" class="space-y-2">
          <label
            v-for="opt in currentQ.options || []"
            :key="opt"
            class="flex cursor-pointer items-center gap-2.5 rounded-lg border px-3 py-2 transition-colors"
            :class="isMultiChecked(currentQ.id, opt) ? 'border-blue-400 bg-blue-50/70 text-blue-900' : 'border-gray-200 hover:bg-gray-50'"
          >
            <input
              type="checkbox"
              :checked="isMultiChecked(currentQ.id, opt)"
              class="accent-blue-600"
              @change="toggleMulti(currentQ.id, opt)"
            />
            <span>{{ opt }}</span>
          </label>
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
              class="min-w-0 flex-1 bg-transparent text-[13px] outline-none"
              @focus="ensureMultiChecked(currentQ.id, '__CUSTOM__')"
            />
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
        <el-button
          size="small"
          plain
          :disabled="submitting"
          :loading="submitting && activeOption === 'SKIP_QUESTION'"
          @click="submitSkip"
        >
          跳过，让AI自行决定
        </el-button>
        <div class="flex items-center gap-2">
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
            :disabled="submitting"
            @click="submitAnswers"
          >
            提交回答
          </el-button>
        </div>
      </div>
    </template>

    <!-- 2. 已作答留痕条 -->
    <div v-else-if="card.status === 'ANSWERED'" class="px-3.5 py-2 text-xs text-blue-900">
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <span>💬</span>
          <span>已补充 {{ questions.length }} 项需求细节</span>
        </div>
        <button
          type="button"
          class="text-blue-600 hover:underline"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起详情 ▴' : '展开查看 ▾' }}
        </button>
      </div>
      <div v-if="expanded" class="mt-2 space-y-1 rounded-lg bg-white/80 p-2.5 text-gray-700">
        <div v-for="(q, idx) in questions" :key="q.id || idx" class="flex gap-2">
          <span class="font-medium text-gray-500">Q{{ idx + 1 }}:</span>
          <span>{{ formatSavedAnswer(q.id) }}</span>
        </div>
      </div>
    </div>

    <!-- 3. 跳过 / 软超时 / 失效 -->
    <div v-else class="flex items-center gap-2 px-3.5 py-2 text-xs text-gray-500">
      <span>⏭️</span>
      <span v-if="card.status === 'TIMED_OUT_SOFT'">问答等待超时（60s），已自动跳过并由 AI 按默认假设继续</span>
      <span v-else-if="card.status === 'SKIPPED'">已跳过需求补充，AI 将按默认假设继续</span>
      <span v-else>该需求澄清提问已结束（{{ card.status }}）</span>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { decideHitlDecision } from '@/api/hitl'

const props = defineProps({
  card: { type: Object, required: true },
  highlighted: { type: Boolean, default: false }
})

const emit = defineEmits(['decided'])

const currentStep = ref(0)
const submitting = ref(false)
const activeOption = ref('')
const expanded = ref(false)

const singleMap = reactive({})
const multiMap = reactive({})
const customInputs = reactive({})
const textAnswers = reactive({})

const questions = computed(() => {
  const raw = props.card?.rawPayload
  if (raw && Array.isArray(raw.questions)) return raw.questions
  return []
})

const currentQ = computed(() => questions.value[currentStep.value] || null)

const progressPercent = computed(() => {
  if (!questions.value.length) return 100
  return Math.round(((currentStep.value + 1) / questions.value.length) * 100)
})

const wrapperClass = computed(() => {
  if (props.card.status === 'PENDING') {
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
  if (props.card.status !== 'PENDING' || submitting.value) return
  if (e.target && e.target.tagName === 'TEXTAREA') return
  e.preventDefault()
  if (currentStep.value < questions.value.length - 1) {
    currentStep.value++
  } else {
    submitAnswers()
  }
}

async function submitAnswers() {
  await sendDecide('SUBMIT_ANSWERS', buildAnswersPayload())
}

async function submitSkip() {
  await sendDecide('SKIP_QUESTION', null)
}

async function sendDecide(option, answerJson) {
  if (submitting.value) return
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
    ElMessage.error(msg)
  } finally {
    submitting.value = false
    activeOption.value = ''
  }
}
</script>

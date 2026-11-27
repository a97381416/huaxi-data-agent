<template>
  <div class="flex items-center">
    <!-- 顶栏待办铃铛按钮 -->
    <button
      type="button"
      class="relative flex h-9 items-center gap-1.5 rounded-full px-3 text-xs font-medium transition-colors duration-200"
      :class="pendingList.length ? 'bg-amber-50 text-amber-700 hover:bg-amber-100' : 'text-gray-500 hover:bg-gray-100'"
      @click="openDrawer"
    >
      <el-icon :size="15"><Bell /></el-icon>
      <span>待我处理</span>
      <span
        v-if="pendingList.length"
        class="inline-flex min-w-[18px] items-center justify-center rounded-full bg-red-500 px-1.5 text-[11px] font-semibold leading-[18px] text-white"
      >
        {{ pendingList.length }}
      </span>
    </button>

    <!-- 右侧全局待办汇聚抽屉 -->
    <el-drawer
      v-model="visible"
      title="待我处理"
      direction="rtl"
      size="400px"
      append-to-body
    >
      <template #header>
        <div class="flex items-center justify-between pr-2">
          <div class="flex items-center gap-2 text-sm font-semibold text-gray-900">
            <span>待我处理 ({{ pendingList.length }})</span>
          </div>
          <div class="flex flex-wrap items-center gap-1 rounded-lg bg-gray-100 p-0.5 text-xs">
            <button
              v-for="tab in filterTabs"
              :key="tab.value"
              type="button"
              class="rounded-md px-2 py-1 transition-colors"
              :class="filterType === tab.value ? 'bg-white font-medium text-gray-900 shadow-xs' : 'text-gray-500'"
              @click="filterType = tab.value"
            >
              {{ tab.label }}
            </button>
          </div>
        </div>
      </template>

      <div v-if="loading" class="py-10 text-center text-xs text-gray-400">加载中…</div>
      <div v-else-if="!filteredList.length" class="py-14 text-center text-xs text-gray-400">
        当前没有待处理的事项
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="item in filteredList"
          :key="item.decisionId"
          class="rounded-xl border p-3.5 transition-all hover:shadow-xs"
          :class="cardBoxClass(item.type)"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-1.5 text-xs font-semibold" :class="cardTitleClass(item.type)">
                <span class="rounded px-1.5 py-0.5 text-[11px]" :class="badgeClass(item.type)">
                  [{{ typeText(item.type) }}]
                </span>
                <span class="truncate">{{ item.actionSummary || item.actionLabel || item.toolName }}</span>
              </div>
              <div class="mt-1.5 text-xs text-gray-500">
                会话：{{ item.conversationTitle || item.conversationId }}
              </div>
              <div class="mt-1 text-[11px]" :class="remainClass(item.type)">
                剩余 {{ formatRemain(item.remainingSeconds) }}
              </div>
            </div>
            <el-button size="small" type="primary" plain @click="goHandle(item)">
              去处理
            </el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { listPendingHitlDecisions } from '@/api/hitl'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const visible = ref(false)
const loading = ref(false)
const pendingList = ref([])
const filterType = ref('ALL')
let pollTimer = null

const filterTabs = [
  { value: 'ALL', label: '全部' },
  { value: 'PERMISSION', label: '授权' },
  { value: 'VERIFY_OUTCOME', label: '核对' },
  { value: 'QUESTION', label: '提问' },
  { value: 'PLAN_CONFIRM', label: '计划' }
]

const URGENCY_ORDER = { VERIFY_OUTCOME: 1, PERMISSION: 2, PLAN_CONFIRM: 3, QUESTION: 4 }

const filteredList = computed(() => {
  const list = filterType.value === 'ALL'
    ? [...pendingList.value]
    : pendingList.value.filter((it) => it.type === filterType.value)
  return list.sort((a, b) => (URGENCY_ORDER[a.type] || 9) - (URGENCY_ORDER[b.type] || 9))
})

function typeText(type) {
  if (type === 'VERIFY_OUTCOME') return '核对'
  if (type === 'QUESTION') return '提问'
  if (type === 'PLAN_CONFIRM') return '计划'
  return '授权'
}

function cardBoxClass(type) {
  if (type === 'VERIFY_OUTCOME') return 'border-orange-200 bg-orange-50/40 hover:border-orange-300'
  if (type === 'QUESTION') return 'border-blue-200 bg-blue-50/40 hover:border-blue-300'
  if (type === 'PLAN_CONFIRM') return 'border-indigo-200 bg-indigo-50/40 hover:border-indigo-300'
  return 'border-amber-200 bg-amber-50/40 hover:border-amber-300'
}

function cardTitleClass(type) {
  if (type === 'VERIFY_OUTCOME') return 'text-orange-900'
  if (type === 'QUESTION') return 'text-blue-900'
  if (type === 'PLAN_CONFIRM') return 'text-indigo-900'
  return 'text-amber-900'
}

function badgeClass(type) {
  if (type === 'VERIFY_OUTCOME') return 'bg-orange-100 text-orange-700'
  if (type === 'QUESTION') return 'bg-blue-100 text-blue-700'
  if (type === 'PLAN_CONFIRM') return 'bg-indigo-100 text-indigo-700'
  return 'bg-amber-100 text-amber-700'
}

function remainClass(type) {
  if (type === 'VERIFY_OUTCOME') return 'text-orange-700'
  if (type === 'QUESTION') return 'text-blue-700'
  if (type === 'PLAN_CONFIRM') return 'text-indigo-700'
  return 'text-amber-700'
}

async function refreshPending() {
  if (!userStore.token) return
  try {
    const list = await listPendingHitlDecisions()
    pendingList.value = Array.isArray(list) ? list : []
  } catch {
    /* 静默失败，避免轮询打扰 */
  }
}

async function openDrawer() {
  visible.value = true
  loading.value = true
  try {
    await refreshPending()
  } finally {
    loading.value = false
  }
}

function goHandle(item) {
  visible.value = false
  router.push({
    path: '/chat',
    query: {
      conversationId: item.conversationId,
      focusDecision: item.decisionId
    }
  })
}

function formatRemain(seconds) {
  const s = Math.max(0, Number(seconds || 0))
  const m = String(Math.floor(s / 60)).padStart(2, '0')
  const sec = String(s % 60).padStart(2, '0')
  return `${m}:${sec}`
}

function onHitlChanged() {
  refreshPending()
}

onMounted(() => {
  refreshPending()
  pollTimer = setInterval(refreshPending, 15000)
  window.addEventListener('hitl-pending-changed', onHitlChanged)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('hitl-pending-changed', onHitlChanged)
})
</script>

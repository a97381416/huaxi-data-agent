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
          <div class="flex items-center gap-1 rounded-lg bg-gray-100 p-0.5 text-xs">
            <button
              type="button"
              class="rounded-md px-2 py-1 transition-colors"
              :class="filterType === 'ALL' ? 'bg-white font-medium text-gray-900 shadow-xs' : 'text-gray-500'"
              @click="filterType = 'ALL'"
            >
              全部
            </button>
            <button
              type="button"
              class="rounded-md px-2 py-1 transition-colors"
              :class="filterType === 'PERMISSION' ? 'bg-white font-medium text-gray-900 shadow-xs' : 'text-gray-500'"
              @click="filterType = 'PERMISSION'"
            >
              授权
            </button>
          </div>
        </div>
      </template>

      <div v-if="loading" class="py-10 text-center text-xs text-gray-400">加载中…</div>
      <div v-else-if="!filteredList.length" class="py-14 text-center text-xs text-gray-400">
        当前没有待审批的事项
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="item in filteredList"
          :key="item.decisionId"
          class="rounded-xl border border-amber-200 bg-amber-50/40 p-3.5 transition-all hover:border-amber-300 hover:shadow-xs"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-1.5 text-xs font-semibold text-amber-900">
                <span class="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] text-amber-700">[授权]</span>
                <span class="truncate">{{ item.actionSummary || item.actionLabel || item.toolName }}</span>
              </div>
              <div class="mt-1.5 text-xs text-gray-500">
                会话：{{ item.conversationTitle || item.conversationId }}
              </div>
              <div class="mt-1 text-[11px] text-amber-700">
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

const filteredList = computed(() => {
  if (filterType.value === 'ALL') return pendingList.value
  return pendingList.value.filter((it) => it.type === filterType.value)
})

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

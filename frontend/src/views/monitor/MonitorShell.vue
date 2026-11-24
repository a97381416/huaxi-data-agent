<template>
  <div class="monitor-shell flex h-full min-h-0 flex-col bg-[#f7f8fa]" @keydown="onKeydown">
    <!-- 二级页签条：监控模块内导航（已融入主框架，无独立顶栏/侧栏） -->
    <div class="flex h-11 shrink-0 items-center gap-1 border-b border-gray-100 bg-white px-4">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="no-underline relative flex h-11 items-center gap-1.5 px-3 text-sm transition-colors duration-200"
        :class="isActive(item.path)
          ? 'font-semibold text-gray-900'
          : 'font-medium text-gray-500 hover:text-gray-900'"
        :title="item.label"
      >
        <el-icon :size="14"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
        <!-- 选中底部指示条（与主框架顶栏 Tab 同款） -->
        <span
          v-if="isActive(item.path)"
          class="absolute inset-x-2.5 bottom-0 h-0.5 rounded-full bg-blue-600"
        ></span>
      </router-link>

      <div class="flex-1"></div>

      <!-- 全局搜索入口（Ctrl+K 命令面板） -->
      <button
        class="flex h-8 cursor-pointer items-center gap-1.5 rounded-lg border border-gray-200 bg-gray-50 px-2.5 text-xs text-gray-400 transition-colors hover:border-blue-300"
        title="搜索 user_id / session_id / conversation_id / 问题…"
        @click="paletteOpen = true"
      >
        <el-icon :size="13"><Search /></el-icon>
        <span>搜索会话</span>
        <kbd
          class="rounded border border-gray-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-gray-400"
        >Ctrl K</kbd>
      </button>
    </div>

    <!-- 页面内容（各页路由出口，滚动发生在页面内部） -->
    <main class="min-h-0 min-w-0 flex-1 overflow-auto">
      <router-view />
    </main>

    <CommandPalette v-model="paletteOpen" />
    <DiagnoseDrawer />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { DataLine, Histogram, Document, Search, Setting } from '@element-plus/icons-vue'
import CommandPalette from '@/components/CommandPalette.vue'
import DiagnoseDrawer from '@/components/DiagnoseDrawer.vue'

const route = useRoute()

const paletteOpen = ref(false)

/** 二级页签（原独立侧栏降级而来：总览/提问跟踪/报告/规则管理） */
const navItems = [
  { path: '/monitor', label: '总览', icon: DataLine },
  { path: '/monitor/traces', label: '提问跟踪', icon: Histogram },
  { path: '/monitor/reports', label: '报告', icon: Document },
  { path: '/monitor/rules', label: '规则管理', icon: Setting }
]

function isActive(path) {
  if (path === '/monitor') return route.path === '/monitor'
  return route.path.startsWith(path)
}

/** Ctrl+K 全局快捷键（原独立框架顶栏逻辑保留） */
function onGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = true
  }
}

/** 面板内键盘：ESC 关闭（组件内已处理），此处兜底全局 */
function onKeydown(e) {
  if (e.key === 'Escape' && paletteOpen.value) {
    paletteOpen.value = false
  }
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onUnmounted(() => window.removeEventListener('keydown', onGlobalKeydown))
</script>

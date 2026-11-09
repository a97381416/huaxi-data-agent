<template>
  <div class="layout">
    <!-- 顶栏 h-14：品牌 + Tab 导航（选中加粗+底部指示条）+ 用户区 -->
    <header
      class="flex h-14 shrink-0 items-center gap-6 border-b border-gray-100 bg-white px-5"
    >
      <!-- 品牌 Logo 区（极简） -->
      <div class="flex shrink-0 items-center gap-2.5">
        <span
          class="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600 text-white shadow-sm"
        >
          <el-icon :size="16"><Monitor /></el-icon>
        </span>
        <span class="whitespace-nowrap text-[15px] font-semibold tracking-tight text-gray-900">
          数据分析智能体
        </span>
      </div>

      <!-- 中间 Tab 导航 -->
      <nav class="flex h-14 min-w-0 flex-1 items-center justify-center gap-1">
        <template v-for="t in navTabs" :key="t.path">
          <router-link
            :to="t.path"
            class="no-underline relative flex h-14 items-center gap-1.5 px-3.5 text-sm transition-colors duration-200"
            :class="route.path === t.path ? 'font-semibold text-gray-900' : 'font-medium text-gray-500 hover:text-gray-900'"
          >
            <span>{{ t.label }}</span>
            <!-- 选中底部指示条 -->
            <span
              v-if="route.path === t.path"
              class="absolute inset-x-3 bottom-0 h-0.5 rounded-full bg-blue-600"
            ></span>
          </router-link>
        </template>
      </nav>

      <!-- 右侧用户区 -->
      <el-dropdown trigger="click" @command="onCommand">
        <span class="flex h-9 cursor-pointer items-center gap-2 rounded-full pl-1 pr-2 outline-none transition-colors duration-200 hover:bg-gray-100">
          <span class="flex h-8 w-8 items-center justify-center rounded-full bg-blue-600 text-sm font-semibold text-white">
            {{ avatarText }}
          </span>
          <span class="text-sm font-medium text-gray-700">
            {{ userStore.user?.nickname || userStore.user?.username }}
          </span>
          <el-icon :size="12" class="text-gray-400"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu class="user-menu">
            <el-dropdown-item command="logout" class="user-menu-item">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <el-main
      class="layout-main"
      :class="{ 'layout-main--flush': isFlushRoute, 'layout-main--chat': route.path === '/chat' }"
    >
      <router-view />
    </el-main>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Monitor } from '@element-plus/icons-vue'
import { fetchMe, logout } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const avatarText = computed(
  () => (userStore.user?.nickname || userStore.user?.username || '?').slice(0, 1)
)

/** 顶栏 Tab：admin 专属管理页仅 admin 可见（用户管理/评测报告暂不进菜单，URL 仍可达） */
const navTabs = computed(() => {
  const tabs = [{ path: '/chat', label: '智能对话' }]
  if (userStore.isAdmin) {
    // tabs.push({ path: '/users', label: '用户管理' })
    tabs.push({ path: '/skills', label: '技能管理' })
    tabs.push({ path: '/mcp', label: 'MCP 服务' })
    // tabs.push({ path: '/eval', label: '评测报告' })
  }
  return tabs
})

/** 全出血页面（去外边距铺满）：对话工作台 + 三个管理页表格 */
const isFlushRoute = computed(() => ['/chat', '/mcp', '/skills'].includes(route.path))

// 进入主框架时刷新一次用户信息（M0 验收：/auth/me 返回角色/部门/档案）；
// 无 token（未登录被守卫重定向前的短暂挂载）不发请求，避免 401 toast 噪音
onMounted(async () => {
  if (!userStore.token) return
  try {
    const vo = await fetchMe()
    userStore.setUser(vo)
  } catch {
    /* 401 已由拦截器统一处理 */
  }
})

async function onCommand(cmd) {
  if (cmd === 'logout') {
    try {
      await logout()
    } catch {
      /* 无论后端结果如何都本地登出 */
    }
    userStore.clear()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f9fafb;
}

.layout-main {
  background: #f9fafb;
}

/* 智能对话页：去外边距全出血铺满（页面内部自带留白与侧栏） */
.layout-main--flush {
  padding: 0;
}

/* 智能对话页：外层禁止滚动（高度由 flex 精确分配，滚动发生在页面内部消息流/侧栏） */
.layout-main--chat {
  overflow: hidden;
}

/* 用户下拉（popper 挂 body，但元素由本组件渲染，scoped 属性可命中） */
.user-menu {
  min-width: 128px;
  padding: 6px;
  border: 1px solid #f3f4f6;
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.08);
}

.user-menu-item {
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: #4b5563;
}

.user-menu-item:hover {
  background: #fef2f2;
  color: #dc2626;
}
</style>

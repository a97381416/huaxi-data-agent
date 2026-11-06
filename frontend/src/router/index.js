import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { milestones } from '@/testhub/scenarios'

/** 测试功能区：/test/m0 ~ /test/m8，同一页面组件按里程碑渲染 */
function testMenuRoutes() {
  return milestones.map((x) => ({
    path: `test/m${x.m}`,
    name: `test-m${x.m}`,
    component: () => import('@/views/TestHubView.vue'),
    props: { m: x.m },
    meta: { title: `测试 M${x.m} · ${x.short}` }
  }))
}

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      {
        // 首页/内核演示不再进菜单：默认落地智能对话工作台（页面组件保留，URL 可直达）
        path: '',
        redirect: '/chat'
      },
      {
        path: 'kernel',
        name: 'kernel-demo',
        component: () => import('@/views/KernelDemoView.vue'),
        meta: { title: '内核演示' }
      },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话' }
      },
      {
        path: 'users',
        name: 'user-manage',
        component: () => import('@/views/UserManageView.vue'),
        meta: { title: '用户管理', adminOnly: true }
      },
      {
        path: 'skills',
        name: 'skill-manage',
        component: () => import('@/views/SkillManageView.vue'),
        meta: { title: '技能管理', adminOnly: true }
      },
      {
        path: 'mcp',
        name: 'mcp-manage',
        component: () => import('@/views/McpManageView.vue'),
        meta: { title: 'MCP 服务', adminOnly: true }
      },
      {
        path: 'eval',
        name: 'eval-report',
        component: () => import('@/views/EvalReportView.vue'),
        meta: { title: '评测报告', adminOnly: true }
      },
      ...testMenuRoutes(),
      {
        path: 'test',
        redirect: '/test/m0'
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫：非公开页面无 token 一律跳登录页；adminOnly 页面非 admin 重定向首页
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!to.meta.public && !userStore.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && userStore.token) {
    return { path: '/' }
  }
  if (to.meta.adminOnly && !userStore.isAdmin) {
    return { path: '/' }
  }
})

export default router

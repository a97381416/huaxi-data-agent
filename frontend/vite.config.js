import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 开发期自清理 Service Worker（仅 dev 生效，产物不带）
// 背景：本端口此前跑过别的 PWA（如 pet-app-v2），它的 SW 注册在 scope "/" 且不会自动注销，
// 会持续拦截并缓存我们的静态资源——表现是控制台 "Response body is already used" 报错、
// 以及偶发加载到旧代码。这里提供一个「装完即注销自己」的脚本：Chrome 下次加载时会用新脚本
// 替换旧注册（字节不同→触发更新），随即把它注销掉，之后本项目页面不再被任何 SW 控制。
function devSelfUnregisterSw() {
  return {
    name: 'dev-self-unregister-sw',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/sw.js', (req, res) => {
        res.setHeader('Content-Type', 'application/javascript')
        res.setHeader('Cache-Control', 'no-cache')
        res.end(
          "self.addEventListener('install', (event) => {\n" +
          '  event.waitUntil(self.registration.unregister())\n' +
          '})\n',
        )
      })
    },
  }
}

// dev 用 Vite proxy 代理到后端（默认 8889，可用环境变量 DATA_AGENT_API_TARGET 覆盖，
// 如本机 8889 被旧实例占用时指向新实例：DATA_AGENT_API_TARGET=http://localhost:8890 npm run dev）；
// 生产构建产物（dist/）由后端静态资源或 nginx 托管
const dataAgentTarget = process.env.DATA_AGENT_API_TARGET || 'http://localhost:8889'

export default defineConfig({
  plugins: [vue(), tailwindcss(), devSelfUnregisterSw()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: true, // 监听 0.0.0.0，允许通过本机局域网 IP（如 192.168.2.142）访问
    port: 5173,
    proxy: {
      '/auth': { target: dataAgentTarget, changeOrigin: true },
      '/sys': { target: dataAgentTarget, changeOrigin: true },
      '/agent': { target: dataAgentTarget, changeOrigin: true },
      '/session': { target: dataAgentTarget, changeOrigin: true },
      // SPA 路由 /monitor*（监控中心页面）与监控 API /monitor/* 同前缀：
      // 浏览器整页导航（Accept: text/html）放行给 SPA index.html，axios 接口请求照常代理后端
      '/monitor': {
        target: dataAgentTarget,
        changeOrigin: true,
        bypass(req) {
          if ((req.headers.accept || '').includes('text/html')) return '/index.html'
        }
      },
      '/api': { target: dataAgentTarget, changeOrigin: true },
      // M-E1：业务文件直传主平台（/infra/file/upload 等 /admin-api/*）。
      // 默认指向联调主后端（与 agent 后端 local profile 的 action base-url 一致）；
      // 本地起主后端时用环境变量覆盖：MAIN_API_TARGET=http://127.0.1:9031 pnpm dev。
      // 生产由 nginx 同域转发（M-H4 规划内）。
      '/admin-api': { target: process.env.MAIN_API_TARGET || 'http://192.168.2.77:9031', changeOrigin: true }
    }
  }
})

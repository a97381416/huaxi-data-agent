import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// dev 用 Vite proxy 代理到后端 8889；生产构建产物（dist/）由后端静态资源或 nginx 托管
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: true, // 监听 0.0.0.0，允许通过本机局域网 IP（如 192.168.2.142）访问
    port: 5173,
    proxy: {
      '/auth': { target: 'http://localhost:8889', changeOrigin: true },
      '/sys': { target: 'http://localhost:8889', changeOrigin: true },
      '/agent': { target: 'http://localhost:8889', changeOrigin: true },
      '/session': { target: 'http://localhost:8889', changeOrigin: true },
      // SPA 路由 /monitor*（监控中心页面）与监控 API /monitor/* 同前缀：
      // 浏览器整页导航（Accept: text/html）放行给 SPA index.html，axios 接口请求照常代理后端
      '/monitor': {
        target: 'http://localhost:8889',
        changeOrigin: true,
        bypass(req) {
          if ((req.headers.accept || '').includes('text/html')) return '/index.html'
        }
      },
      '/api': { target: 'http://localhost:8889', changeOrigin: true },
      // M-E1：业务文件直传主平台（/infra/file/upload 等 /admin-api/*）。
      // 默认指向联调主后端（与 agent 后端 local profile 的 action base-url 一致）；
      // 本地起主后端时用环境变量覆盖：MAIN_API_TARGET=http://127.0.0.1:9031 pnpm dev。
      // 生产由 nginx 同域转发（M-H4 规划内）。
      '/admin-api': { target: process.env.MAIN_API_TARGET || 'http://192.168.2.77:9031', changeOrigin: true }
    }
  }
})

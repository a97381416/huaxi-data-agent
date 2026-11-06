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
    port: 5173,
    proxy: {
      '/auth': { target: 'http://localhost:8889', changeOrigin: true },
      '/sys': { target: 'http://localhost:8889', changeOrigin: true },
      '/agent': { target: 'http://localhost:8889', changeOrigin: true },
      '/session': { target: 'http://localhost:8889', changeOrigin: true },
      '/api': { target: 'http://localhost:8889', changeOrigin: true }
    }
  }
})

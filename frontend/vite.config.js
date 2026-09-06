import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置
// 开发环境下 /api 请求通过代理转发到后端 8080, 避免跨域
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        // 与后端 .env 的 SERVER_PORT 保持一致
        target: 'http://localhost:8087',
        changeOrigin: true
      }
    }
  }
})

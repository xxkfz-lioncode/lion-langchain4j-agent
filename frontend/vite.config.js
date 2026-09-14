import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置
// 开发环境下 /api 请求通过代理转发到后端 8087, 避免跨域;
// /swagger-ui 与 /v3/api-docs 同样代理: 让内嵌的「接口文档」页与后端保持同源,
// 这样 Swagger UI 里的 Try it out 请求会走同源地址再被代理到后端, 不会触发跨域。
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
      },
      // Swagger UI 静态资源(swagger-ui-bundle.js / swagger-ui.css 等)
      '/swagger-ui': {
        target: 'http://localhost:8087',
        changeOrigin: true
      },
      // OpenAPI 描述文件(内嵌文档页初始化时拉取)
      '/v3/api-docs': {
        target: 'http://localhost:8087',
        changeOrigin: true
      }
    }
  }
})

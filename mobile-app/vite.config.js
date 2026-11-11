import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// 后端地址(与后端 .env 的 SERVER_PORT 保持一致)
const BACKEND = 'http://localhost:8087'

export default defineConfig({
  plugins: [uni()],
  server: {
    host: true, // 监听 0.0.0.0, 手机同一 WiFi 可用 http://<电脑IP>:5174 访问(默认只监听 localhost)
    port: 5174, // 与 web 前端(5173)错开, 两套前端可同时起 dev server
    // H5 与 web 端同架构: 页面内请求走同源相对路径, 由 Vite 代理转发到后端,
    // 浏览器视角无跨域 => 无 CORS 预检, 后端 CorsConfig 对 H5 不再是必需
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true
      }
    }
  }
})

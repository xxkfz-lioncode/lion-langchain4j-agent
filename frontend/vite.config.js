import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址(与后端 .env 的 SERVER_PORT 保持一致)
const BACKEND = 'http://localhost:8087'

// Vite 配置
// 开发环境下所有后端请求统一经代理转发, 避免跨域;
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
      // 业务接口
      '/api': {
        target: BACKEND,
        changeOrigin: true
      },
      // 联调测试接口(controller.test 包, 免登录)与天气工具演示接口,
      // 让「接口文档」页里的这些接口也能在线调试(它们不在 /api 前缀下)
      '/test': {
        target: BACKEND,
        changeOrigin: true
      },
      '/weather': {
        target: BACKEND,
        changeOrigin: true
      },
      // Swagger UI 静态资源(swagger-ui-bundle.js / swagger-ui.css 等)
      '/swagger-ui': {
        target: BACKEND,
        changeOrigin: true
      },
      // OpenAPI 描述文件(文档页初始化时拉取)
      // 注意: 这里必须保留原始 Host(changeOrigin 为 false)。
      // springdoc 是「按请求的 Host」生成 spec 里 servers 地址的(实测: 换 127.0.0.1 访问,
      // servers 就变成 http://127.0.0.1:8087)。一旦开启 changeOrigin, Host 会被替换成
      // localhost:8087, spec 里 servers 也跟着变成 http://localhost:8087;
      // 而文档页跑在 5173, Try it out 就成了跨域请求, 被浏览器直接拦掉
      // (Server response: Failed to fetch / CORS)。
      // 保留原始 Host 后, servers 恰好等于浏览器当前访问的地址(5173 或局域网 IP),
      // 与文档页天然同源, 既不用改后端, 也不影响直接访问 8087 的原生文档页。
      '/v3/api-docs': {
        target: BACKEND,
        changeOrigin: false
      }
    }
  }
})

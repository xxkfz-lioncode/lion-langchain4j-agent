<template>
  <div class="api-doc-page">
    <!-- 顶部工具条: 登录态提示 + 重载 / 新窗口打开 -->
    <div class="doc-toolbar">
      <div class="toolbar-left">
        <el-icon class="toolbar-icon"><Document /></el-icon>
        <span class="toolbar-title">接口文档</span>
        <el-tag v-if="auth.isLogin" type="success" size="small" effect="light">
          已自动注入当前登录 token, 可直接调试需登录接口
        </el-tag>
        <el-tag v-else type="warning" size="small" effect="light">
          未登录: 仅免登录接口可直接调用
        </el-tag>
      </div>
      <div class="toolbar-right">
        <el-button size="small" :loading="loading" @click="reload">重载文档</el-button>
        <el-button size="small" type="primary" plain @click="openExternal">
          <el-icon><TopRight /></el-icon>
          <span>新窗口打开</span>
        </el-button>
      </div>
    </div>

    <!-- 加载失败兜底: 给出重试与跳转原生文档的入口 -->
    <div v-if="error" class="doc-error">
      <el-empty :description="error">
        <el-button type="primary" @click="reload">重新加载</el-button>
        <el-button @click="openExternal">在新窗口打开原始文档</el-button>
      </el-empty>
    </div>

    <!-- Swagger UI 挂载点(由 swagger-ui-bundle.js 渲染) -->
    <div v-show="!error" id="swagger-ui" class="doc-container"></div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Document, TopRight } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

// Swagger UI 资源(经 Vite 代理转发到后端 8087)
const SWAGGER_BUNDLE = '/swagger-ui/swagger-ui-bundle.js'
const SWAGGER_CSS = '/swagger-ui/swagger-ui.css'
const OPENAPI_URL = '/v3/api-docs'

const auth = useAuthStore()
const loading = ref(false)
const error = ref('')

/** 原生 Swagger UI 页面地址(新窗口打开 / 兜底跳转) */
function nativeDocUrl() {
  return import.meta.env.VITE_SWAGGER_URL || '/swagger-ui/index.html'
}

function openExternal() {
  window.open(nativeDocUrl(), '_blank')
}

/** 注入 swagger-ui.css(已存在则跳过) */
function loadCss(href) {
  if (document.querySelector(`link[data-swagger-css="${href}"]`)) return
  const link = document.createElement('link')
  link.rel = 'stylesheet'
  link.href = href
  link.dataset.swaggerCss = href
  document.head.appendChild(link)
}

/** 注入 swagger-ui-bundle.js, 返回加载完成的 Promise(全局 SwaggerUIBundle 已存在则直接复用) */
function loadScript(src) {
  if (window.SwaggerUIBundle) return Promise.resolve()

  const cached = document.querySelector(`script[data-swagger-js="${src}"]`)
  if (cached && cached.dataset.loaded === 'true') return Promise.resolve()

  return new Promise((resolve, reject) => {
    const script = cached || document.createElement('script')
    if (!cached) {
      script.src = src
      script.async = true
      script.dataset.swaggerJs = src
      document.body.appendChild(script)
    }
    script.addEventListener(
      'load',
      () => {
        script.dataset.loaded = 'true'
        resolve()
      },
      { once: true }
    )
    script.addEventListener('error', () => reject(new Error(`资源加载失败: ${src}`)), {
      once: true
    })
  })
}

/** 渲染(或重渲染)Swagger UI */
async function render() {
  loading.value = true
  error.value = ''
  try {
    loadCss(SWAGGER_CSS)
    await loadScript(SWAGGER_BUNDLE)
    if (!window.SwaggerUIBundle) throw new Error('swagger-ui-bundle 未就绪, 请确认后端已启动')

    const el = document.getElementById('swagger-ui')
    if (el) el.innerHTML = ''

    window.ui = window.SwaggerUIBundle({
      url: OPENAPI_URL,
      dom_id: '#swagger-ui',
      deepLinking: true,
      // 分组展开, 打开即可看到全部接口
      docExpansion: 'list',
      defaultModelsExpandDepth: 1,
      // 与后端 springdoc 配置保持一致: 标签/接口均按字母排序
      tagsSorter: 'alpha',
      operationsSorter: 'alpha',
      // 默认开启 Try it out, 少点一次按钮
      tryItOutEnabled: true,
      // 记住 Authorize 里手动填写的凭据
      persistAuthorization: true,
      displayRequestDuration: true,
      presets: [window.SwaggerUIBundle.presets.apis],
      plugins: [window.SwaggerUIBundle.plugins.DownloadUrl],
      layout: 'BaseLayout',
      // 关键: 每次请求前自动补上 satoken 请求头, 无需手动 Authorize
      requestInterceptor: (req) => {
        if (auth.token) {
          req.headers = req.headers || {}
          req.headers['satoken'] = auth.token
        }
        return req
      },
      onComplete: () => {
        loading.value = false
      }
    })
  } catch (e) {
    loading.value = false
    error.value = e?.message || '接口文档加载失败'
  }
}

function reload() {
  render()
}

onMounted(render)

onBeforeUnmount(() => {
  const el = document.getElementById('swagger-ui')
  if (el) el.innerHTML = ''
})
</script>

<style scoped>
.api-doc-page {
  height: 100%;
  min-height: 480px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
}

.doc-toolbar {
  height: 52px;
  flex-shrink: 0;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar-icon {
  font-size: 18px;
  color: #2d4a8f;
}

.toolbar-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.doc-container {
  flex: 1;
  overflow: auto;
  padding: 0 16px 16px;
}

.doc-error {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 隐藏 Swagger UI 顶部的信息块留白, 让接口列表更紧凑 */
.doc-container :deep(.swagger-ui .info) {
  margin: 16px 0;
}
</style>

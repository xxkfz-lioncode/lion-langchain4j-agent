import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/auth/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  // 兼容旧地址 /login
  { path: '/login', redirect: '/auth/login' },
  {
    path: '/',
    component: () => import('@/layout/Layout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页', requiresAuth: true }
      },
      // 分类一: 对话与知识
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '对话助手', parentTitle: '对话与知识', requiresAuth: true }
      },
      {
        path: 'rag',
        name: 'rag',
        component: () => import('@/views/RagView.vue'),
        meta: { title: 'RAG 知识库', parentTitle: '对话与知识', requiresAuth: true }
      },
      {
        path: 'vector',
        name: 'vector',
        component: () => import('@/views/VectorView.vue'),
        meta: { title: '向量库查询', parentTitle: '对话与知识', requiresAuth: true }
      },
      {
        path: 'ai-services',
        name: 'ai-services',
        component: () => import('@/views/AiServicesView.vue'),
        meta: { title: 'AI Services 指南', requiresAuth: true }
      },
      // 分类二: 智能分析
      {
        path: 'sentiment',
        name: 'sentiment',
        component: () => import('@/views/SentimentView.vue'),
        meta: { title: '情感分析', parentTitle: '智能分析', requiresAuth: true }
      },
      {
        path: 'structured',
        name: 'structured',
        component: () => import('@/views/StructuredOutputView.vue'),
        meta: { title: '结构化输出', parentTitle: '智能分析', requiresAuth: true }
      },
      {
        path: 'data-query',
        name: 'data-query',
        component: () => import('@/views/DataChatView.vue'),
        meta: { title: '问数对话', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 数据模型(元数据只读浏览 + 手动刷新)
        path: 'schema-model',
        name: 'schema-model',
        component: () => import('@/views/manage/SchemaModelView.vue'),
        meta: { title: '数据模型', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 订单数据(只读浏览)
        path: 'data-orders',
        name: 'data-orders',
        component: () => import('@/views/manage/OrderDataView.vue'),
        meta: { title: '订单数据', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 问数日志(只读审计)
        path: 'data-logs',
        name: 'data-logs',
        component: () => import('@/views/manage/QueryLogView.vue'),
        meta: { title: '问数日志', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 反馈记录(只读 + 处理动作)
        path: 'data-feedback',
        name: 'data-feedback',
        component: () => import('@/views/manage/FeedbackManageView.vue'),
        meta: { title: '反馈管理', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 业务术语(增删改查)
        path: 'term-manage',
        name: 'term-manage',
        component: () => import('@/views/manage/TermManageView.vue'),
        meta: { title: '术语管理', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // 脱敏规则(增删改查)
        path: 'mask-rule-manage',
        name: 'mask-rule-manage',
        component: () => import('@/views/manage/MaskRuleManageView.vue'),
        meta: { title: '脱敏规则', parentTitle: '智能问数', requiresAuth: true }
      },
      {
        // Few-shot 示例库(增删改查)
        path: 'fewshot-manage',
        name: 'fewshot-manage',
        component: () => import('@/views/manage/FewshotManageView.vue'),
        meta: { title: '示例库管理', parentTitle: '智能问数', requiresAuth: true }
      },
      // 分类三: 系统管理
      {
        path: 'user',
        name: 'user',
        component: () => import('@/views/UserView.vue'),
        meta: { title: '用户管理', parentTitle: '系统管理', requiresAuth: true }
      },
      {
        path: 'llm-log',
        name: 'llm-log',
        component: () => import('@/views/ObservabilityView.vue'),
        meta: { title: 'LLM 调用日志', parentTitle: '系统管理', requiresAuth: true }
      },
      {
        path: 'api-doc',
        name: 'api-doc',
        component: () => import('@/views/ApiDocView.vue'),
        meta: { title: '接口文档', parentTitle: '系统管理', requiresAuth: true }
      },
      // 分类四: 工具与协议
      {
        path: 'mcp',
        name: 'mcp',
        component: () => import('@/views/McpView.vue'),
        meta: { title: 'MCP 调试', parentTitle: '工具与协议', requiresAuth: true }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫: 需要登录的页面未登录则跳转登录页
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLogin) {
    return { path: '/auth/login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLogin) {
    return { path: '/home' }
  }
  return true
})

export default router

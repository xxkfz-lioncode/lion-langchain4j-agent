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
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '对话助手', requiresAuth: true }
      },
      {
        path: 'user',
        name: 'user',
        component: () => import('@/views/UserView.vue'),
        meta: { title: '用户管理', requiresAuth: true }
      },
      {
        path: 'system/menu',
        name: 'system-menu',
        component: () => import('@/views/PlaceholderView.vue'),
        props: {
          title: '菜单管理',
          description: '该模块用于配置系统导航菜单, 当前功能开发中。'
        },
        meta: { title: '菜单管理', parentTitle: '系统管理', requiresAuth: true }
      },
      {
        path: 'system/setting',
        name: 'system-setting',
        component: () => import('@/views/PlaceholderView.vue'),
        props: {
          title: '参数设置',
          description: '该模块用于配置系统运行参数, 当前功能开发中。'
        },
        meta: { title: '参数设置', parentTitle: '系统管理', requiresAuth: true }
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

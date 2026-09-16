<template>
  <div class="app-layout">
    <!-- 左侧: 侧边栏(Logo + 菜单) -->
    <aside class="app-aside" :class="{ collapsed }">
      <div class="app-logo">
        <span class="logo-mark">
          <svg viewBox="0 0 32 32" width="30" height="30">
            <defs>
              <linearGradient id="lg" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#3e6cc7" />
                <stop offset="1" stop-color="#2d4a8f" />
              </linearGradient>
            </defs>
            <rect x="2" y="2" width="28" height="28" rx="7" fill="url(#lg)" />
            <path
              d="M11 22 V10 h3 l4 6 4-6 h3 v12 h-3 v-7l-4 6-4-6 v7 z"
              fill="#fff"
            />
          </svg>
        </span>
        <span v-show="!collapsed" class="logo-title">Lion Agent</span>
      </div>

      <el-scrollbar class="app-menu-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          unique-opened
          router
          class="app-menu"
        >
          <el-menu-item index="/home">
            <el-icon><HomeFilled /></el-icon>
            <span>首页</span>
          </el-menu-item>

          <!-- 分类一: 对话与知识 -->
          <el-sub-menu index="group-chat">
            <template #title>
              <el-icon><ChatLineSquare /></el-icon>
              <span>对话与知识</span>
            </template>
            <el-menu-item index="/chat">
              <el-icon><ChatDotRound /></el-icon>
              <span>对话助手</span>
            </el-menu-item>
            <el-menu-item index="/rag">
              <el-icon><Collection /></el-icon>
              <span>RAG 知识库</span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 分类二: 智能分析 -->
          <el-sub-menu index="group-analysis">
            <template #title>
              <el-icon><DataLine /></el-icon>
              <span>智能分析</span>
            </template>
            <el-menu-item index="/sentiment">
              <el-icon><MagicStick /></el-icon>
              <span>情感分析</span>
            </el-menu-item>
            <el-menu-item index="/structured">
              <el-icon><DataAnalysis /></el-icon>
              <span>结构化输出</span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 分类三: 系统管理 -->
          <el-sub-menu index="group-system">
            <template #title>
              <el-icon><Setting /></el-icon>
              <span>系统管理</span>
            </template>
            <el-menu-item v-if="auth.isAdmin" index="/user">
              <el-icon><User /></el-icon>
              <span>用户管理</span>
            </el-menu-item>
            <el-menu-item index="/api-doc">
              <el-icon><Document /></el-icon>
              <span>接口文档</span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
    </aside>

    <!-- 右侧: 顶栏 + 标签页 + 内容区 -->
    <div class="app-right">
      <!-- 顶部导航栏 -->
      <header class="app-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="toggleCollapse">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="item in crumbs" :key="item">
              {{ item }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <el-tooltip content="刷新" placement="bottom">
            <el-icon class="header-icon" @click="handleRefresh"><Refresh /></el-icon>
          </el-tooltip>
          <el-tooltip content="全屏" placement="bottom">
            <el-icon class="header-icon" @click="toggleFullscreen"><FullScreen /></el-icon>
          </el-tooltip>

          <el-dropdown trigger="click" @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="28" class="user-avatar">
                {{ (auth.nickname || '用户').charAt(0) }}
              </el-avatar>
              <span class="user-name">{{ auth.nickname || '用户' }}</span>
              <el-icon class="user-arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="refresh">刷新页面</el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 标签页 Tabs -->
      <div class="app-tabs">
        <el-tag
          v-for="tab in tabs"
          :key="tab.path"
          class="tab-item"
          :class="{ active: tab.path === route.path }"
          :closable="tab.path !== HOME"
          @click="goTab(tab.path)"
          @close="closeTab(tab.path)"
        >
          {{ tab.title }}
        </el-tag>
      </div>

      <!-- 内容区 -->
      <main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  HomeFilled,
  ChatLineSquare,
  ChatDotRound,
  Collection,
  DataLine,
  MagicStick,
  DataAnalysis,
  Setting,
  User,
  Document,
  Expand,
  Fold,
  Refresh,
  FullScreen,
  ArrowDown,
  SwitchButton
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { getInfo, logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const HOME = '/home'
const collapsed = ref(false)

const activeMenu = computed(() => route.path)
const crumbs = computed(() => {
  const meta = route.meta || {}
  const arr = []
  if (meta.parentTitle) arr.push(meta.parentTitle)
  if (meta.title) arr.push(meta.title)
  return arr.length ? arr : ['首页']
})

// ---- 标签页 / Tab ----
const tabs = ref([{ path: HOME, title: '首页' }])

function addTab(r) {
  if (r.path === HOME) return
  if (!tabs.value.find((t) => t.path === r.path)) {
    tabs.value.push({ path: r.path, title: r.meta?.title || '未命名' })
  }
}

watch(
  () => route.path,
  () => addTab(route),
  { immediate: true }
)

function goTab(path) {
  if (path !== route.path) router.push(path)
}

function closeTab(path) {
  const idx = tabs.value.findIndex((t) => t.path === path)
  if (idx === -1) return
  tabs.value.splice(idx, 1)
  if (path === route.path) {
    const next = tabs.value[idx] || tabs.value[idx - 1] || tabs.value[0]
    router.push(next ? next.path : HOME)
  }
}

// ---- 顶栏操作 ----
function toggleCollapse() {
  collapsed.value = !collapsed.value
}

function handleRefresh() {
  location.reload()
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen()
  } else {
    document.documentElement.requestFullscreen()
  }
}

function handleCommand(command) {
  if (command === 'refresh') {
    handleRefresh()
  } else if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗?', '提示', { type: 'warning' })
      .then(async () => {
        logout().catch(() => {})
        auth.clear()
        router.push('/auth/login')
      })
      .catch(() => {})
  }
}

// ---- 初始化: 拉取当前用户信息, 刷新角色(兼容已有登录会话 / 直接访问) ----
onMounted(async () => {
  try {
    const info = await getInfo()
    auth.setRole(info?.role || '')
  } catch (e) {
    // 拉取失败时不阻断页面渲染
  }
})
</script>

<style scoped>
.app-layout {
  height: 100%;
  display: flex;
  background: #f0f2f5;
}

/* 侧边栏 */
.app-aside {
  width: 210px;
  transition: width 0.28s;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #e6e6e6;
  flex-shrink: 0;
}

.app-aside.collapsed {
  width: 64px;
}

.app-logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid #e6e6e6;
  overflow: hidden;
  white-space: nowrap;
}

.logo-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.logo-title {
  font-size: 18px;
  font-weight: 700;
  color: #2d4a8f;
  letter-spacing: 1px;
}

.app-menu-scroll {
  flex: 1;
}

.app-menu {
  border-right: none;
}

::deep(.app-menu .el-menu-item.is-active) {
  color: #2d4a8f;
  background: #eef2fb;
}

::deep(.app-menu .el-menu-item:hover),
::deep(.app-menu .el-sub-menu__title:hover) {
  background: #f5f7fa;
}

/* 分组标题: 字号略小、颜色略淡, 与叶子菜单区分层级 */
::deep(.app-menu .el-sub-menu__title) {
  color: #606266;
  font-weight: 500;
}

/* 分组内有激活项时, 分组标题也高亮, 便于定位 */
::deep(.app-menu .el-sub-menu.is-active > .el-sub-menu__title) {
  color: #2d4a8f;
}

/* 次级菜单项缩进后左侧加一条细线, 强化层级关系 */
::deep(.app-menu .el-menu .el-menu-item) {
  min-width: auto;
}

/* 右侧主区域 */
.app-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 顶部导航栏 */
.app-header {
  height: 56px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: #2d4a8f;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.header-icon {
  font-size: 18px;
  cursor: pointer;
  color: #606266;
}

.header-icon:hover {
  color: #2d4a8f;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #606266;
}

.user-avatar {
  background: #2d4a8f;
  color: #fff;
}

.user-name {
  font-size: 14px;
}

.user-arrow {
  font-size: 12px;
}

/* 标签页 */
.app-tabs {
  height: 40px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
  flex-shrink: 0;
  overflow-x: auto;
}

.tab-item {
  cursor: pointer;
  user-select: none;
  background: #fff;
  color: #606266;
  border: 1px solid #e4e7ed;
}

.tab-item.active {
  color: #fff;
  background: #2d4a8f;
  border-color: #2d4a8f;
}

/* 内容区 */
.app-content {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

.fade-transform-enter-active,
.fade-transform-leave-active {
  transition: all 0.3s;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateX(-20px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>

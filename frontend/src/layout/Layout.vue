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

      <!-- 菜单: 纯手写实现(替代 el-menu/el-sub-menu)。
           背景: EP el-sub-menu 在分组开合重渲时存在 DOM 复用 bug, 会导致相邻分组标题图标丢失,
           多种 workaround(结构包裹/动态 key 重建)均无效, 故整体重构为数据驱动的原生实现。
           展开/折叠/高亮/折叠窄栏全部由下面的状态与样式控制, 不依赖 EP 的菜单 DOM 机制 -->
      <nav class="app-menu">
        <template v-for="m in MENU" :key="m.type === 'item' ? m.path : m.key">
          <!-- 一级菜单项 -->
          <div
            v-if="m.type === 'item'"
            class="menu-row"
            :class="{ active: route.path === m.path }"
            @click="router.push(m.path)"
          >
            <component :is="m.icon" class="menu-ico" />
            <span v-show="!collapsed" class="menu-label">{{ m.label }}</span>
          </div>

          <!-- 分组(可展开/折叠, 单开) -->
          <div v-else class="menu-group">
            <div
              class="menu-row group-head"
              :class="{ active: isGroupActive(m), open: isOpen(m) }"
              @click="toggleGroup(m)"
            >
              <component :is="m.icon" class="menu-ico" />
              <span v-show="!collapsed" class="menu-label">{{ m.label }}</span>
              <el-icon v-show="!collapsed" class="menu-arrow" :class="{ open: isOpen(m) }">
                <ArrowDown />
              </el-icon>
            </div>
            <div v-show="isOpen(m)" class="menu-sub">
              <div
                v-for="c in groupChildren(m)"
                :key="c.path"
                class="menu-row sub-row"
                :class="{ active: route.path === c.path }"
                @click="router.push(c.path)"
              >
                <component :is="c.icon" class="menu-ico" />
                <span v-show="!collapsed" class="menu-label">{{ c.label }}</span>
              </div>
            </div>
          </div>
        </template>
      </nav>
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

      <!-- 标签页 Tabs: 左键切换 / × 关闭 / 中键关闭 / 右键上下文菜单 -->
      <div class="app-tabs" @contextmenu.prevent>
        <el-tag
          v-for="tab in tabs"
          :key="tab.path"
          class="tab-item"
          :class="{ active: tab.path === route.path }"
          :closable="tab.path !== HOME"
          @click="goTab(tab.path)"
          @close="closeTab(tab.path)"
          @mousedown.middle.prevent="closeTab(tab.path)"
          @contextmenu.prevent="openCtxMenu($event, tab)"
        >
          {{ tab.title }}
        </el-tag>
      </div>

      <!-- 标签右键菜单(Teleport 到 body, 不受 .app-tabs overflow 裁剪) -->
      <Teleport to="body">
        <div
          v-if="ctx.visible"
          class="tab-ctx-menu"
          :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }"
          @click.stop
        >
          <div class="ctx-item" @click="onCtxCommand('refresh')">刷新</div>
          <div class="ctx-item" :class="{ disabled: !canCloseCtx }" @click="onCtxCommand('close')">
            关闭当前
          </div>
          <div class="ctx-divider" />
          <div class="ctx-item" :class="{ disabled: !canCloseOthers }" @click="onCtxCommand('closeOthers')">
            关闭其他
          </div>
          <div class="ctx-item" :class="{ disabled: !canCloseLeft }" @click="onCtxCommand('closeLeft')">
            关闭左侧
          </div>
          <div class="ctx-item" :class="{ disabled: !canCloseRight }" @click="onCtxCommand('closeRight')">
            关闭右侧
          </div>
          <div class="ctx-divider" />
          <div class="ctx-item" @click="onCtxCommand('closeAll')">关闭全部</div>
        </div>
      </Teleport>

      <!-- 内容区: key 带 reloadKey, 支持"刷新"只重渲染当前组件而非整页 reload -->
      <main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" :key="route.path + '::' + reloadKey" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  HomeFilled,
  ChatLineSquare,
  ChatDotRound,
  Collection,
  DataLine,
  MagicStick,
  DataAnalysis,
  DataBoard,
  Grid,
  Tickets,
  Lock,
  Setting,
  User,
  Document,
  Expand,
  Fold,
  Refresh,
  FullScreen,
  ArrowDown,
  SwitchButton,
  Connection,
  Tools,
  Coin,
  SetUp,
  Odometer
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { getInfo, logout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const HOME = '/home'
const collapsed = ref(false)

// ---- 菜单配置(数据驱动, 替代模板里手写的 el-menu-item/el-sub-menu) ----
const MENU = [
  { type: 'item', path: '/home', label: '首页', icon: HomeFilled },
  { type: 'item', path: '/chat', label: '对话助手', icon: ChatDotRound },
  { type: 'item', path: '/ai-services', label: 'AI Services 指南', icon: SetUp },
  {
    type: 'group', key: 'kb', label: '知识库', icon: Collection,
    children: [
      { path: '/rag', label: '知识库管理', icon: Document },
      { path: '/rag-chat', label: '知识问答', icon: Tickets },
      { path: '/vector', label: '向量库查询', icon: Coin }
    ]
  },
  {
    type: 'group', key: 'data-query', label: '智能问数', icon: DataBoard,
    children: [
      { path: '/data-query', label: '问数对话', icon: ChatDotRound },
      { path: '/schema-model', label: '数据模型', icon: Coin },
      { path: '/data-orders', label: '订单数据', icon: Tickets },
      { path: '/data-logs', label: '问数日志', icon: Document },
      { path: '/data-feedback', label: '反馈管理', icon: ChatLineSquare },
      { path: '/term-manage', label: '术语管理', icon: Collection },
      { path: '/mask-rule-manage', label: '脱敏规则', icon: Lock },
      { path: '/fewshot-manage', label: '示例库管理', icon: Grid }
    ]
  },
  {
    type: 'group', key: 'analysis', label: '智能分析', icon: DataLine,
    children: [
      { path: '/sentiment', label: '情感分析', icon: MagicStick },
      { path: '/structured', label: '结构化输出', icon: DataAnalysis }
    ]
  },
  {
    type: 'group', key: 'system', label: '系统管理', icon: Setting,
    children: [
      { path: '/user', label: '用户管理', icon: User, adminOnly: true },
      { path: '/llm-log', label: 'LLM 调用日志', icon: Odometer },
      { path: '/api-doc', label: '接口文档', icon: Document }
    ]
  },
  {
    type: 'group', key: 'tooling', label: '工具与协议', icon: Connection,
    children: [
      { path: '/mcp', label: 'MCP 调试', icon: Tools }
    ]
  }
]

// 分组展开状态(单开, 等价于原 unique-opened); 默认展开当前路由所在分组
const openGroup = ref('')
const isOpen = (m) => openGroup.value === m.key
function toggleGroup(m) {
  openGroup.value = isOpen(m) ? '' : m.key
}
// 管理员可见性: adminOnly 子项仅管理员可见, 全部不可见时分组也不显示
const groupChildren = (m) => m.children.filter((c) => !c.adminOnly || auth.isAdmin)
const isGroupActive = (m) => groupChildren(m).some((c) => c.path === route.path)

// 路由变化时自动展开所在分组(首次进入页面也能定位到)
watch(
  () => route.path,
  (p) => {
    const g = MENU.find((m) => m.type === 'group' && m.children.some((c) => c.path === p))
    if (g) openGroup.value = g.key
  },
  { immediate: true }
)

const crumbs = computed(() => {
  const meta = route.meta || {}
  const arr = []
  if (meta.parentTitle) arr.push(meta.parentTitle)
  if (meta.title) arr.push(meta.title)
  return arr.length ? arr : ['首页']
})

// ---- 标签页 / Tab ----
// 标签持久化: 刷新浏览器后保留已打开的标签(生产后台标配); 首页固定且始终在第一位
const TABS_STORAGE_KEY = 'lion-layout-tabs'
function loadTabs() {
  try {
    const saved = JSON.parse(localStorage.getItem(TABS_STORAGE_KEY) || '[]')
      .filter((t) => t && t.path && t.title && t.path !== HOME)
    return [{ path: HOME, title: '首页' }, ...saved]
  } catch {
    return [{ path: HOME, title: '首页' }]
  }
}
const tabs = ref(loadTabs())
watch(tabs, (v) => localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(v)), { deep: true })

// 组件级刷新: key 变化只重渲染当前路由组件, 不整页 reload(会重置当前页状态, 符合刷新语义)
const reloadKey = ref(0)

// 右键菜单状态(Teleport 到 body, 用 clientX/clientY 定位)
const ctx = reactive({ visible: false, x: 0, y: 0, path: '' })
const ctxIndex = computed(() => tabs.value.findIndex((t) => t.path === ctx.path))
const canCloseCtx = computed(() => ctx.path !== HOME)
const canCloseOthers = computed(() =>
  tabs.value.some((t) => t.path !== HOME && t.path !== ctx.path))
const canCloseLeft = computed(() =>
  tabs.value.slice(0, Math.max(ctxIndex.value, 0)).some((t) => t.path !== HOME))
const canCloseRight = computed(() =>
  tabs.value.slice(ctxIndex.value + 1).some((t) => t.path !== HOME))

function openCtxMenu(e, tab) {
  ctx.path = tab.path
  // 贴近视口边缘时向内收, 菜单不会被裁掉
  ctx.x = Math.min(e.clientX, window.innerWidth - 160)
  ctx.y = Math.min(e.clientY, window.innerHeight - 250)
  ctx.visible = true
}
function closeCtxMenu() {
  ctx.visible = false
}

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
  if (path === HOME) return // 首页固定, 中键/右键都关不掉
  const idx = tabs.value.findIndex((t) => t.path === path)
  if (idx === -1) return
  tabs.value.splice(idx, 1)
  if (path === route.path) {
    const next = tabs.value[idx] || tabs.value[idx - 1] || tabs.value[0]
    router.push(next ? next.path : HOME)
  }
}

/** 批量关闭: 首页永保留; 若当前激活页被关, 跳到剩余最后一个 */
function closeMany(paths) {
  const toClose = new Set(paths.filter((p) => p !== HOME))
  const wasActive = toClose.has(route.path)
  tabs.value = tabs.value.filter((t) => !toClose.has(t.path))
  if (wasActive) {
    router.push(tabs.value[tabs.value.length - 1]?.path || HOME)
  }
}

/** 刷新指定标签: 非当前页先切过去, 再重渲染(不整页 reload) */
async function refreshTab(path) {
  if (path !== route.path) {
    await router.push(path)
  }
  reloadKey.value++
}

function onCtxCommand(cmd) {
  const idx = ctxIndex.value
  ctx.visible = false
  switch (cmd) {
    case 'refresh': refreshTab(ctx.path); break
    case 'close': if (canCloseCtx.value) closeTab(ctx.path); break
    case 'closeOthers':
      if (canCloseOthers.value) {
        closeMany(tabs.value.filter((t) => t.path !== HOME && t.path !== ctx.path).map((t) => t.path))
      }
      break
    case 'closeLeft': if (canCloseLeft.value) closeMany(tabs.value.slice(0, idx).map((t) => t.path)); break
    case 'closeRight': if (canCloseRight.value) closeMany(tabs.value.slice(idx + 1).map((t) => t.path)); break
    case 'closeAll': closeMany(tabs.value.map((t) => t.path)); break
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

// ---- 右键菜单全局收起: 点外部 / 页面滚动 / Esc ----
function onGlobalKeydown(e) {
  if (e.key === 'Escape') closeCtxMenu()
}

// ---- 初始化: 拉取当前用户信息, 刷新角色(兼容已有登录会话 / 直接访问) ----
onMounted(async () => {
  document.addEventListener('click', closeCtxMenu)
  document.addEventListener('scroll', closeCtxMenu, true)
  document.addEventListener('keydown', onGlobalKeydown)
  try {
    const info = await getInfo()
    auth.setRole(info?.role || '')
  } catch (e) {
    // 拉取失败时不阻断页面渲染
  }
})

onUnmounted(() => {
  document.removeEventListener('click', closeCtxMenu)
  document.removeEventListener('scroll', closeCtxMenu, true)
  document.removeEventListener('keydown', onGlobalKeydown)
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

/* 菜单(手写实现) */
.app-menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 6px 0;
}

.menu-row {
  height: 44px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  margin: 2px 8px;
  border-radius: 6px;
  cursor: pointer;
  color: #606266;
  font-size: 14px;
  user-select: none;
  white-space: nowrap;
}

.menu-row:hover {
  background: #f5f7fa;
}

.menu-row.active {
  color: #2d4a8f;
  background: #eef2fb;
  font-weight: 500;
}

/* 显式像素尺寸: 图标组件 svg 默认 width=1em(相对字号), 一旦字号继承链异常就撑满整行,
   挤掉文字标签并纵向溢出行高互相叠压; 固定 px 后与字号彻底解耦 */
.menu-ico {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.menu-label {
  margin-left: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 分组标题: 层级略弱于叶子菜单的激活态 */
.menu-row.group-head {
  color: #606266;
  font-weight: 500;
}

.menu-row.group-head .menu-ico {
  font-size: 17px;
}

.menu-arrow {
  margin-left: auto;
  width: 12px;
  height: 12px;
  font-size: 12px;
  transition: transform 0.2s;
}

.menu-arrow.open {
  transform: rotate(180deg);
}

/* 次级菜单: 缩进 + 左侧细线, 强化层级 */
.menu-sub .sub-row {
  padding-left: 42px;
  height: 40px;
  position: relative;
}

.menu-sub .sub-row .menu-ico {
  width: 15px;
  height: 15px;
}

.menu-sub .sub-row.active::before {
  content: '';
  position: absolute;
  left: 26px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: #2d4a8f;
}

/* 折叠窄栏: 仅图标, 居中 */
.app-aside.collapsed .menu-row {
  justify-content: center;
  padding: 0;
  margin: 2px 10px;
}

.app-aside.collapsed .menu-sub .sub-row {
  padding-left: 0;
}

.app-aside.collapsed .menu-sub .sub-row.active::before {
  left: 10px;
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
  border: 1px solid #e4e7eb;
}

.tab-item.active {
  color: #fff;
  background: #2d4a8f;
  border-color: #2d4a8f;
}

/* 标签右键菜单(Teleport 到 body, fixed 定位) */
.tab-ctx-menu {
  position: fixed;
  z-index: 3000;
  width: 140px;
  padding: 4px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  font-size: 13px;
  user-select: none;
}
.ctx-item {
  height: 32px;
  line-height: 32px;
  padding: 0 14px;
  color: #303133;
  cursor: pointer;
}
.ctx-item:hover:not(.disabled) {
  color: #2d4a8f;
  background: #eef2fb;
}
.ctx-item.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}
.ctx-divider {
  height: 1px;
  margin: 4px 0;
  background: #ebeef5;
  pointer-events: none;
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

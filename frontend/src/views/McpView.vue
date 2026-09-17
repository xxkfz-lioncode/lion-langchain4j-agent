<template>
  <div class="mcp-page">
    <!-- 顶部: 连接状态栏 -->
    <header class="mcp-header">
      <div class="mcp-header-left">
        <div class="mcp-title">
          <el-icon class="mcp-title-icon"><Connection /></el-icon>
          <span>MCP 调试控制台</span>
          <span class="mcp-sub">Model Context Protocol · 工具列表 / Schema 详情 / 模型对话</span>
        </div>
      </div>
      <div class="mcp-header-right">
        <el-tag v-if="info" :type="statusType" effect="dark" round class="status-tag">
          <el-icon class="status-dot"><Loading v-if="loading" /><CircleCheck v-else-if="mcpOk" /><Warning v-else /></el-icon>
          {{ statusText }}
        </el-tag>
        <el-tag v-if="info" type="info" effect="plain" round class="url-tag" :title="info.url || '未配置'">
          {{ info.url || 'URL 未配置' }}
        </el-tag>
        <el-button :loading="loading" :icon="Refresh" size="small" @click="refreshAll">刷新</el-button>
      </div>
    </header>

    <!-- 主体: 三栏布局 -->
    <div class="mcp-body">
      <!-- 左: 工具列表 -->
      <aside class="tool-side">
        <div class="side-header">
          <span class="side-title">工具列表</span>
          <el-badge :value="tools.length" :max="99" type="primary" class="count-badge" />
        </div>
        <div v-if="!loading && tools.length === 0" class="empty-tip">
          <el-icon class="empty-icon"><Box /></el-icon>
          <p>暂无可用工具</p>
          <p class="hint">{{ toolsError || '请检查 application.yml 中 lion.mcp.url 是否正确' }}</p>
        </div>
        <el-scrollbar class="tool-list" v-else>
          <div
            v-for="t in tools"
            :key="t.name"
            class="tool-item"
            :class="{ active: selectedTool?.name === t.name }"
            @click="selectTool(t)"
          >
            <div class="tool-item-name">
              <el-icon class="tool-icon"><Tools /></el-icon>
              <span>{{ t.name }}</span>
            </div>
            <div class="tool-item-desc">{{ t.description || '(无描述)' }}</div>
          </div>
        </el-scrollbar>
      </aside>

      <!-- 中: 工具详情 -->
      <section class="detail-pane">
        <div class="pane-header">
          <span class="pane-title">
            <el-icon><Document /></el-icon>
            工具详情
          </span>
          <div v-if="selectedTool" class="pane-actions">
            <el-tag size="small" type="info" effect="plain">{{ selectedTool.name }}</el-tag>
            <el-button size="small" :icon="CopyDocument" @click="copyJson(selectedTool)">复制 Schema</el-button>
          </div>
        </div>
        <div class="pane-body" v-if="selectedTool">
          <div class="detail-row">
            <div class="detail-label">名称</div>
            <div class="detail-value mono">{{ selectedTool.name }}</div>
          </div>
          <div class="detail-row">
            <div class="detail-label">描述</div>
            <div class="detail-value">{{ selectedTool.description || '(无)' }}</div>
          </div>
          <div class="detail-row detail-row-block">
            <div class="detail-label">参数 Schema</div>
            <pre class="schema-block">{{ formatSchema(selectedTool.parameters) }}</pre>
          </div>
        </div>
        <div v-else class="pane-empty">
          <el-icon class="pane-empty-icon"><Aim /></el-icon>
          <p>从左侧选择一个工具查看详情</p>
        </div>
      </section>

      <!-- 右: 对话测试 -->
      <section class="chat-pane">
        <div class="pane-header">
          <span class="pane-title">
            <el-icon><ChatLineRound /></el-icon>
            对话测试
          </span>
          <el-button
            size="small"
            :disabled="loading || streaming"
            :icon="Brush"
            @click="clearChat"
          >清空对话</el-button>
        </div>

        <main ref="listRef" class="chat-body">
          <div v-if="!messages.length && !streaming" class="chat-empty">
            <el-icon class="chat-empty-icon"><Promotion /></el-icon>
            <p>试试输入一句自然语言指令</p>
            <p class="hint">模型会自动判断是否调用 MCP 工具, 例如"现在几点了"会触发 now 工具</p>
          </div>
          <div
            v-for="(m, i) in messages"
            :key="i"
            class="msg-row"
            :class="m.role"
          >
            <div class="msg-avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="msg-bubble">
              <div class="msg-text markdown-body" v-html="renderMd(m.content)" />
            </div>
          </div>
          <div v-if="streaming && messages[messages.length - 1]?.role === 'assistant'" class="msg-row assistant streaming">
            <div class="msg-avatar">AI</div>
            <div class="msg-bubble">
              <div class="msg-text markdown-body" v-html="renderMd(currentReply) + CURSOR_HTML" />
            </div>
          </div>
        </main>

        <footer class="chat-footer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="3"
            resize="none"
            :disabled="streaming"
            placeholder="输入指令后 Enter 发送, Shift+Enter 换行"
            @keydown.enter.exact.prevent="handleSend"
          />
          <el-button
            type="primary"
            class="send-btn"
            :loading="streaming"
            :disabled="streaming"
            @click="handleSend"
          >发送</el-button>
        </footer>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Connection,
  Tools,
  Document as DocIcon,
  CopyDocument,
  Refresh,
  Loading,
  CircleCheck,
  Warning,
  Box,
  Aim,
  ChatLineRound,
  Promotion,
  Brush
} from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  getMcpInfo,
  listMcpTools,
  mcpChatStream
} from '@/api/mcp'

// Element Plus 图标里 Document 与上面的 Document 别名冲突, 重命名使用
const Document = DocIcon

// ====================== 状态 ======================
const loading = ref(false)
const streaming = ref(false)
const info = ref(null)
const tools = ref([])
const toolsError = ref('')
const selectedTool = ref(null)

const mcpOk = computed(() => info.value && info.value.toolCount > 0)
const statusType = computed(() => {
  if (!info.value) return 'info'
  return mcpOk.value ? 'success' : 'warning'
})
const statusText = computed(() => {
  if (!info.value) return '加载中'
  if (!info.value.enabled) return 'MCP 未启用'
  if (!info.value.url) return 'URL 未配置'
  if (info.value.toolCount < 0) return '握手失败'
  if (info.value.toolCount === 0) return '已连接 · 无工具'
  return `已连接 · ${info.value.toolCount} 个工具`
})

// ====================== 对话 ======================
const input = ref('')
const messages = ref([])
const currentReply = ref('')
const listRef = ref()

const CURSOR_HTML = '<span class="stream-cursor"></span>'

function renderMd(text = '') {
  const raw = String(text || '')
  try {
    return DOMPurify.sanitize(marked.parse(raw, { breaks: true, gfm: true }))
  } catch {
    return raw
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

// ====================== 数据加载 ======================
async function refreshAll() {
  loading.value = true
  toolsError.value = ''
  try {
    const [i, t] = await Promise.all([getMcpInfo(), listMcpTools().catch((e) => {
      // listTools 失败时 info 已拿到, 用 info 的 toolCount; 这里不抛, 仅记错误展示
      toolsError.value = e?.message || '拉取工具列表失败'
      return []
    })])
    info.value = i
    tools.value = t || []
    if (info.value?.toolsError) toolsError.value = info.value.toolsError
    // 默认选中第一个工具
    if (tools.value.length && (!selectedTool.value || !tools.value.find((x) => x.name === selectedTool.value.name))) {
      selectedTool.value = tools.value[0]
    } else if (!tools.value.length) {
      selectedTool.value = null
    }
  } catch (e) {
    ElMessage.error('加载 MCP 信息失败: ' + (e?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

function selectTool(t) {
  selectedTool.value = t
}

function formatSchema(p) {
  if (p === null || p === undefined) return '(无参数)'
  // p 已是 JS 对象/数组, 直接 stringify; 控制缩进
  try {
    return JSON.stringify(p, null, 2)
  } catch {
    return String(p)
  }
}

async function copyJson(tool) {
  const text = JSON.stringify(tool, null, 2)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败, 请手动复制')
  }
}

// ====================== 对话发送 ======================
async function handleSend() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  streaming.value = true
  currentReply.value = ''

  // 平滑输出: 模型一次性吐大段也能稳定打字机
  let reply = ''
  let shown = ''
  let timer = null

  function pump() {
    if (shown.length < reply.length) {
      const remain = reply.length - shown.length
      const step = Math.max(1, Math.round(remain / 12))
      shown += reply.slice(shown.length, shown.length + step)
      currentReply.value = shown
      scrollToBottom()
      timer = setTimeout(pump, 16)
    } else {
      finish()
    }
  }

  function finish() {
    if (timer) clearTimeout(timer)
    timer = null
    messages.value.push({ role: 'assistant', content: reply || '(空回复)' })
    currentReply.value = ''
    streaming.value = false
    scrollToBottom()
  }

  try {
    await mcpChatStream(text, undefined, {
      onToken: (token) => {
        reply += token
        if (!timer) pump()
      },
      onDone: () => {
        if (!reply) currentReply.value = '(空回复)'
        pump()
      },
      onError: (e) => {
        messages.value.push({ role: 'assistant', content: '请求失败: ' + (e?.message || '未知错误') })
        streaming.value = false
      }
    })
  } catch (e) {
    // 错误已在 onError 处理, 此处兜底
    if (streaming.value) {
      streaming.value = false
    }
  }
}

function clearChat() {
  messages.value = []
  currentReply.value = ''
  ElMessage.success('对话已清空')
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.mcp-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #f0f2f5;
}

/* 顶部状态栏 */
.mcp-header {
  background: #fff;
  border-radius: 10px;
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.mcp-header-left {
  min-width: 0;
}

.mcp-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.mcp-title-icon {
  color: #2d4a8f;
  font-size: 22px;
}

.mcp-sub {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
  margin-left: 4px;
}

.mcp-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.status-dot {
  font-size: 12px;
}

.url-tag {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 主体三栏 */
.mcp-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 280px 1fr 1fr;
  gap: 12px;
}

/* 工具列表 */
.tool-side {
  background: #fff;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.side-header {
  height: 44px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.side-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.count-badge :deep(.el-badge__content) {
  font-size: 11px;
  height: 16px;
  line-height: 16px;
  padding: 0 5px;
}

.tool-list {
  flex: 1;
}

.tool-item {
  padding: 12px 16px;
  border-bottom: 1px solid #f5f6f8;
  cursor: pointer;
  transition: background 0.18s;
}

.tool-item:hover {
  background: #f5f7fa;
}

.tool-item.active {
  background: #eef2fb;
}

.tool-item.active .tool-item-name {
  color: #2d4a8f;
  font-weight: 600;
}

.tool-item-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #303133;
}

.tool-icon {
  color: #2d4a8f;
}

.tool-item-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.empty-tip {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  color: #c0c4cc;
  text-align: center;
}

.empty-tip p {
  margin: 6px 0;
  font-size: 13px;
}

.empty-tip .hint {
  font-size: 12px;
  color: #c0c4cc;
}

.empty-icon {
  font-size: 36px;
  color: #dcdfe6;
}

/* 详情 / 对话 面板共用 */
.detail-pane,
.chat-pane {
  background: #fff;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.pane-header {
  height: 44px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.pane-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.pane-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 详情面板 */
.pane-body {
  flex: 1;
  overflow: auto;
  padding: 16px 20px;
}

.pane-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.pane-empty p {
  margin-top: 8px;
  font-size: 13px;
}

.pane-empty-icon {
  font-size: 48px;
  color: #dcdfe6;
}

.detail-row {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  font-size: 13px;
  line-height: 1.6;
}

.detail-row-block {
  flex-direction: column;
  gap: 8px;
}

.detail-label {
  flex-shrink: 0;
  width: 72px;
  color: #909399;
  font-weight: 500;
}

.detail-value {
  flex: 1;
  color: #303133;
  word-break: break-word;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  color: #2d4a8f;
}

.schema-block {
  margin: 0;
  padding: 12px 14px;
  background: #1f2329;
  color: #d4d4d4;
  border-radius: 6px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  max-height: 360px;
  overflow: auto;
  white-space: pre;
}

/* 对话面板 */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  background: #fafbfc;
}

.chat-empty {
  text-align: center;
  padding: 48px 16px;
  color: #c0c4cc;
}

.chat-empty p {
  margin: 6px 0;
  font-size: 13px;
}

.chat-empty .hint {
  font-size: 12px;
}

.chat-empty-icon {
  font-size: 40px;
  color: #dcdfe6;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  flex-shrink: 0;
}

.msg-row.assistant .msg-avatar {
  background: #2d4a8f;
}

.msg-row.user .msg-avatar {
  background: #67c23a;
}

.msg-bubble {
  max-width: 75%;
  padding: 9px 13px;
  border-radius: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-row.assistant .msg-bubble {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 2px;
}

.msg-row.user .msg-bubble {
  background: #2d6cdf;
  color: #fff;
  border-top-right-radius: 2px;
}

.chat-footer {
  padding: 10px 16px 14px;
  border-top: 1px solid #e8eaee;
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.send-btn {
  height: 76px;
}
</style>

<style>
/* 全局: 流式光标与 markdown 排版(v-html 注入, 不在 scoped 作用域内) */
.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: #2d6cdf;
  animation: cursor-blink 0.8s steps(2) infinite;
}

@keyframes cursor-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.markdown-body {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
}
.markdown-body p { margin: 4px 0; }
.markdown-body code {
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  background: #f5f7fa;
  color: #c7254e;
  font-size: 12px;
}
.markdown-body pre {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 5px;
  background: #282c34;
  color: #abb2bf;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  overflow-x: auto;
}
.markdown-body pre code { background: transparent; color: inherit; padding: 0; }
.markdown-body ul, .markdown-body ol { margin: 4px 0; padding-left: 20px; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 {
  margin: 8px 0 4px;
  font-weight: 600;
}
.markdown-body blockquote {
  margin: 6px 0;
  padding: 2px 10px;
  border-left: 3px solid #409eff;
  background: #f0f7ff;
  color: #606266;
}
</style>
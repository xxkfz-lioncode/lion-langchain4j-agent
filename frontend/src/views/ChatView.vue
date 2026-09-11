<template>
  <div class="chat-page">
    <!-- 左侧: 会话列表 -->
    <aside class="conv-side">
      <div class="conv-header">
        <span class="conv-header-title">会话列表</span>
        <el-button
          size="small"
          type="primary"
          :icon="Plus"
          :disabled="loading"
          @click="handleNew"
        >
          新建
        </el-button>
      </div>
      <el-scrollbar class="conv-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === currentId }"
          @click="switchTo(conv.id)"
        >
          <div class="conv-info">
            <div class="conv-name">{{ conv.title }}</div>
            <div class="conv-time">{{ formatTime(conv.updateTime) }}</div>
          </div>
          <el-icon class="conv-del" @click.stop="handleDelete(conv)">
            <Delete />
          </el-icon>
        </div>
        <div v-if="!conversations.length" class="conv-empty">暂无会话</div>
      </el-scrollbar>
    </aside>

    <!-- 右侧: 对话区 -->
    <div class="chat-card">
      <!-- 页面内标题栏 -->
      <div class="chat-toolbar">
        <div class="chat-title">
          <span class="chat-dot"></span>
          对话助手
          <span class="chat-sub">基于千问模型的智能对话 · 流式输出</span>
        </div>
        <el-button size="small" :disabled="loading" @click="handleClear">
          <el-icon class="toolbar-icon"><Brush /></el-icon>
          清空会话
        </el-button>
      </div>

      <!-- 消息列表 -->
      <main ref="listRef" class="chat-body">
        <div
          v-for="(item, index) in messages"
          :key="index"
          class="msg-row"
          :class="item.role"
        >
          <div class="msg-avatar">{{ item.role === 'user' ? '我' : 'AI' }}</div>
          <div class="msg-content">
            <!-- 用户消息原样纯文本展示; AI 消息渲染 markdown -->
            <div
              v-if="item.role === 'assistant'"
              class="msg-bubble markdown-body"
              v-html="renderMd(item.content) + (item.streaming ? CURSOR_HTML : '')"
            ></div>
            <div v-else class="msg-bubble">{{ item.content }}</div>
          </div>
        </div>
        <div v-if="!messages.length && !loading" class="chat-empty">
          开始新的对话吧, 在下方输入你的问题
        </div>
      </main>

      <!-- 输入区 -->
      <footer class="chat-footer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          :disabled="loading"
          placeholder="请输入你的问题, Enter 发送, Shift+Enter 换行"
          @keydown.enter.exact.prevent="handleSend"
        />
        <el-button
          type="primary"
          class="send-btn"
          :loading="loading"
          @click="handleSend"
        >
          发送
        </el-button>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Brush, Plus, Delete } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  chatStream,
  clearChat,
  listConversations,
  createConversation,
  deleteConversation,
  listMessages
} from '@/api/chat'

const listRef = ref()
const input = ref('')
const loading = ref(false)

// ---- 会话 ----
const conversations = ref([])
const currentId = ref(null)
const CURRENT_KEY = 'lion-current-conv'

// 流式输出时挂在 AI 气泡末尾的打字机光标(v-html 内联注入, 样式走非 scoped 全局)
const CURSOR_HTML = '<span class="stream-cursor"></span>'

/** 将 markdown 渲染为安全 HTML(先 marked 转 HTML, 再 DOMPurify 过滤防 XSS) */
function renderMd(text = '') {
  const raw = String(text || '')
  try {
    const html = marked.parse(raw, { breaks: true, gfm: true })
    return DOMPurify.sanitize(html)
  } catch (e) {
    // 解析异常时兜底返回转义后的原文, 避免气泡空白
    return raw
  }
}

const messages = ref([])

/**
 * 过滤出界面需要展示的消息:
 * - 丢弃 role === 'tool' 的工具过程消息(工具返回结果仅作 Agent 内部上下文);
 * - 丢弃 content 为空的 assistant(Agent 发起工具调用时的占位消息, 无正文)。
 * 仅保留「用户提问」与「AI 最终回答」, 避免界面出现空气泡与工具结果冗余。
 */
function visibleMessages(list = []) {
  return list
    .filter((m) => m.role !== 'tool' && !(m.role === 'assistant' && !m.content))
    .map((m) => ({ role: m.role, content: m.content }))
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

/** 拉取会话列表(不改变当前选择) */
async function fetchConversations() {
  conversations.value = (await listConversations()) || []
}

/** 加载指定会话的历史消息 */
async function loadMessages(conversationId) {
  const list = (await listMessages(conversationId)) || []
  messages.value = visibleMessages(list)
  scrollToBottom()
}

/** 初始化: 加载会话列表, 恢复上次会话或取第一个; 一个会话都没有则新建 */
async function initConversations() {
  await fetchConversations()
  if (!conversations.value.length) {
    const conv = await createConversation()
    conversations.value.unshift(conv)
    currentId.value = conv.id
  } else {
    const saved = Number(localStorage.getItem(CURRENT_KEY))
    const target = conversations.value.find((c) => c.id === saved) || conversations.value[0]
    currentId.value = target.id
  }
  localStorage.setItem(CURRENT_KEY, String(currentId.value))
  await loadMessages(currentId.value)
}

/** 切换会话: 加载对应历史消息 */
async function switchTo(id) {
  if (loading.value || id === currentId.value) return
  currentId.value = id
  localStorage.setItem(CURRENT_KEY, String(id))
  await loadMessages(id)
}

/** 新建会话 */
async function handleNew() {
  if (loading.value) return
  const conv = await createConversation()
  conversations.value.unshift(conv)
  currentId.value = conv.id
  localStorage.setItem(CURRENT_KEY, String(conv.id))
  messages.value = []
  scrollToBottom()
}

/** 删除会话(需确认): 若删的是当前会话则切到第一个 */
async function handleDelete(conv) {
  if (loading.value) return
  try {
    await ElMessageBox.confirm(`确定删除会话「${conv.title}」吗? 删除后不可恢复。`, '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return // 用户取消
  }
  await deleteConversation(conv.id)
  conversations.value = conversations.value.filter((c) => c.id !== conv.id)
  ElMessage.success('会话已删除')

  if (conv.id === currentId.value) {
    if (conversations.value.length) {
      currentId.value = conversations.value[0].id
      await loadMessages(currentId.value)
    } else {
      currentId.value = null
      messages.value = []
      await handleNew() // 无会话时自动补一个
    }
    localStorage.setItem(CURRENT_KEY, String(currentId.value))
  }
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (currentId.value == null) {
    const conv = await createConversation()
    conversations.value.unshift(conv)
    currentId.value = conv.id
    localStorage.setItem(CURRENT_KEY, String(conv.id))
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  // 先插入一个空的 AI 气泡, 通过平滑队列逐字填充实现稳定的打字机效果
  const aiMsg = { role: 'assistant', content: '', streaming: true }
  messages.value.push(aiMsg)
  scrollToBottom()

  let reply = '' // 已收到的完整回复(全部 token)
  let shown = '' // 已渲染到界面上的文本
  let timer = null

  // 平滑推进: 即使后端/网络一次性送达大 chunk, 界面也按帧逐字吐出, 保证打字机观感
  function pump() {
    if (shown.length < reply.length) {
      const remain = reply.length - shown.length
      // 按剩余量自适应步长, 末段减速, 前段加速(避免显慢)
      const step = Math.max(1, Math.round(remain / 10))
      shown += reply.slice(shown.length, shown.length + step)
      aiMsg.content = shown
      scrollToBottom()
      timer = setTimeout(pump, 16)
    } else {
      // 全部显示完毕, 收尾
      aiMsg.content = reply
      finish()
    }
  }

  function closeStreaming() {
    if (timer) clearTimeout(timer)
    timer = null
    aiMsg.streaming = false
    loading.value = false
    scrollToBottom()
  }

  // 流式结束后以服务端落库的完整内容为准, 修复 DashScope 0.36 流中断导致只显示部分文本
  async function reconcileFromServer() {
    try {
      const list = (await listMessages(currentId.value)) || []
      const last = [...list].reverse().find((m) => m.role === 'assistant')
      if (last && last.content) {
        aiMsg.content = last.content
        messages.value = visibleMessages(list)
      }
    } catch (e) {
      // 拉取失败则保留本地展示内容
    }
    scrollToBottom()
  }

  // 一轮回复结束(无论流是否中断): 关闭流式并同步服务端完整内容
  function finish() {
    closeStreaming()
    reconcileFromServer()
    fetchConversations() // 发送后刷新标题与排序
  }

  try {
    await chatStream(
      text,
      currentId.value,
      {
        onToken: (token) => {
          reply += token
          if (!timer) pump() // 启动或继续平滑队列
        },
        onDone: () => {
          if (!reply) aiMsg.content = '(空回复)'
          pump()
        }
      }
    )
    if (!reply) aiMsg.content = '(空回复)'
    pump()
  } catch (e) {
    aiMsg.content = '抱歉, 请求失败: ' + (e.message || '未知错误')
    closeStreaming()
  }
}

async function handleClear() {
  if (currentId.value == null) return
  await clearChat(currentId.value)
  messages.value = []
  scrollToBottom()
  ElMessage.success('会话已清空')
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  try {
    await initConversations()
  } catch (e) {
    ElMessage.error('加载会话失败: ' + (e.message || '未知错误'))
  }
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  gap: 16px;
}

/* 左侧会话列表 */
.conv-side {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.conv-header {
  height: 48px;
  padding: 0 12px 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.conv-header-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.conv-list {
  flex: 1;
}

.conv-item {
  padding: 12px 12px 12px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  cursor: pointer;
  border-bottom: 1px solid #f5f6f8;
  transition: background 0.2s;
}

.conv-item:hover {
  background: #f5f7fa;
}

.conv-item.active {
  background: #eef2fb;
}

.conv-item.active .conv-name {
  color: #2d4a8f;
  font-weight: 600;
}

.conv-info {
  min-width: 0;
}

.conv-name {
  font-size: 14px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-time {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.conv-del {
  flex-shrink: 0;
  font-size: 15px;
  color: #c0c4cc;
  opacity: 0;
  transition: color 0.2s, opacity 0.2s;
}

.conv-item:hover .conv-del {
  opacity: 1;
}

.conv-del:hover {
  color: #f56c6c;
}

.conv-empty {
  padding: 24px 0;
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
}

/* 右侧对话卡片 */
.chat-card {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.chat-toolbar {
  height: 52px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.chat-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #67c23a;
}

.chat-sub {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}

.toolbar-icon {
  margin-right: 4px;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 20px;
  background: #fafbfc;
}

.chat-empty {
  padding: 48px 0;
  text-align: center;
  color: #c0c4cc;
  font-size: 14px;
}

.msg-row {
  display: flex;
  margin-bottom: 18px;
  gap: 10px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  flex-shrink: 0;
}

.msg-row.assistant .msg-avatar {
  background: #2d4a8f;
}

.msg-row.user .msg-avatar {
  background: #67c23a;
}

.msg-content {
  max-width: 70%;
  display: flex;
  align-items: flex-start;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-row.assistant .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 2px;
}

.msg-row.user .msg-bubble {
  background: #2d6cdf;
  color: #fff;
  border-top-right-radius: 2px;
}

/* AI 流式输出时末尾的光标 */
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
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.chat-footer {
  padding: 12px 20px 16px;
  border-top: 1px solid #e8eaee;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-shrink: 0;
}

.send-btn {
  height: 80px;
}
</style>

<style>
/* v-html 注入的 markdown 元素不在 scoped 作用域内, 排版样式必须放这里(全局) */
.markdown-body {
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
  color: #303133;
}
.markdown-body > *:first-child {
  margin-top: 0;
}
.markdown-body > *:last-child {
  margin-bottom: 0;
}
.markdown-body p {
  margin: 0 0 10px;
}
.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4,
.markdown-body h5,
.markdown-body h6 {
  margin: 16px 0 10px;
  font-weight: 600;
  line-height: 1.4;
  color: #1f2d3d;
}
.markdown-body h1 {
  font-size: 20px;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 6px;
}
.markdown-body h2 {
  font-size: 18px;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 5px;
}
.markdown-body h3 {
  font-size: 16px;
}
.markdown-body h4 {
  font-size: 15px;
}
.markdown-body ul,
.markdown-body ol {
  margin: 6px 0 10px;
  padding-left: 22px;
}
.markdown-body li {
  margin: 3px 0;
}
.markdown-body code {
  padding: 2px 5px;
  margin: 0 2px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  background: #f5f7fa;
  color: #c7254e;
}
.markdown-body pre {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: 6px;
  overflow-x: auto;
  background: #282c34;
  color: #abb2bf;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.5;
}
.markdown-body pre code {
  padding: 0;
  margin: 0;
  background: transparent;
  color: inherit;
  font-size: 13px;
}
.markdown-body blockquote {
  margin: 10px 0;
  padding: 4px 14px;
  border-left: 4px solid #409eff;
  background: #f0f7ff;
  color: #606266;
}
.markdown-body blockquote p {
  margin: 6px 0;
}
.markdown-body table {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 13px;
}
.markdown-body table th,
.markdown-body table td {
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  text-align: left;
}
.markdown-body table th {
  background: #f5f7fa;
  font-weight: 600;
}
.markdown-body a {
  color: #2d6cdf;
  text-decoration: none;
}
.markdown-body a:hover {
  text-decoration: underline;
}
.markdown-body hr {
  margin: 14px 0;
  border: none;
  border-top: 1px solid #ebeef5;
}
.markdown-body strong {
  font-weight: 600;
}

/* 流式输出末尾的打字机光标(v-html 注入, 故非 scoped) */
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
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>

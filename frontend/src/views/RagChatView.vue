<template>
  <div class="rag-chat-page">
    <div class="chat-card">
      <!-- 顶栏: 标题 + 知识库状态 + 清空 -->
      <div class="chat-toolbar">
        <div class="chat-title">
          <span class="chat-dot" :class="{ off: !segments }"></span>
          知识问答
          <span class="chat-sub">{{ statusText }}</span>
        </div>
        <el-button size="small" :disabled="loading || !segments" @click="handleClear">
          <el-icon class="toolbar-icon"><Brush /></el-icon>
          清空对话
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
            <!-- 等待首字输出: 三点跳动"思考中"占位(避免空气泡只剩一根光标) -->
            <div
              v-if="item.role === 'assistant' && item.streaming && !item.content"
              class="msg-bubble thinking-bubble"
            >
              <span class="thinking-label">思考中</span>
              <span class="thinking-dots"><i></i><i></i><i></i></span>
            </div>
            <!-- AI 回答: markdown 气泡 + 引用来源折叠条(检索层注入, 与提示词片段一一对应) -->
            <div v-else-if="item.role === 'assistant'" class="ai-block">
              <div
                class="msg-bubble markdown-body"
                v-html="renderMd(item.content) + (item.streaming ? CURSOR_HTML : '')"
              ></div>
              <div
                v-if="!item.streaming && item.sources && item.sources.length"
                class="sources-box"
              >
                <button class="sources-toggle" type="button" @click="item.sourcesOpen = !item.sourcesOpen">
                  <el-icon><Collection /></el-icon>
                  引用来源 · {{ item.sources.length }} 条
                  <el-icon class="toggle-arrow" :class="{ open: item.sourcesOpen }"><ArrowDown /></el-icon>
                </button>
                <div v-if="item.sourcesOpen" class="sources-list">
                  <div
                    v-for="(s, si) in item.sources"
                    :key="si"
                    class="source-item"
                    @click="openSource(s)"
                  >
                    <div class="source-head">
                      <span class="source-idx">{{ si + 1 }}</span>
                      <span class="source-name" :title="s.fileName">{{ s.fileName || '未知文档' }}</span>
                      <span v-if="s.score != null" class="source-score">{{ s.score }}%</span>
                    </div>
                    <div class="source-snippet">{{ s.snippet }}</div>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="msg-bubble">{{ item.content }}</div>
          </div>
        </div>

        <div v-if="!messages.length && !loading" class="chat-empty">
          <div class="chat-empty-icon"><el-icon><Reading /></el-icon></div>
          <div class="chat-empty-title">知识库问答</div>
          <div class="chat-empty-desc">{{ emptyDesc }}</div>
          <div v-if="segments" class="chat-empty-hint">
            回答仅依据知识库内容生成, 去知识库管理页可维护文档
          </div>
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
          :disabled="!segments"
          @click="handleSend"
        >
          发送
        </el-button>
      </footer>
    </div>

    <!-- 引用来源详情抽屉: 展示片段全文 -->
    <el-drawer v-model="sourceDrawer" size="40%" :with-header="true">
      <template #header>
        <div class="drawer-title">
          <span class="drawer-name">{{ activeSource?.fileName || '未知文档' }}</span>
          <span v-if="activeSource?.score != null" class="source-score">{{ activeSource.score }}%</span>
        </div>
      </template>
      <pre class="drawer-text">{{ activeSource?.text }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Brush, Reading, Collection, ArrowDown } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ragChat, getRagDocuments } from '@/api/rag'

const listRef = ref()
const input = ref('')
const loading = ref(false)
const messages = ref([])
/** 知识库已入库片段数(>0 才允许提问) */
const segments = ref(0)
/** 知识库中待解析文档数(用于精准提示) */
const unparsedCount = ref(0)

const statusText = computed(() =>
  segments.value ? `已入库 ${segments.value} 个片段 · 千问模型` : '知识库为空 · 千问模型'
)

const emptyDesc = computed(() => {
  if (segments.value) return '在下方输入你的问题, 我会依据知识库文档内容回答'
  if (unparsedCount.value) return '知识库有文档尚未解析, 请先到「知识库管理」解析入库'
  return '先到「知识库管理」上传并解析文档, 再向我提问'
})

// 流式输出时挂在 AI 气泡末尾的打字机光标(v-html 内联注入, 样式走非 scoped 全局)
const CURSOR_HTML = '<span class="stream-cursor"></span>'

/** 将 markdown 渲染为安全 HTML(先 marked 转 HTML, 再 DOMPurify 过滤防 XSS) */
function renderMd(text = '') {
  const raw = String(text || '')
  try {
    return DOMPurify.sanitize(marked.parse(raw, { breaks: true, gfm: true }))
  } catch (e) {
    return raw
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

/** 拉取文档列表, 计算已入库片段数与待解析数(失败不阻断页面) */
async function loadKnowledgeStatus() {
  try {
    const list = (await getRagDocuments()) || []
    segments.value = list.reduce((sum, d) => sum + (d.segmentCount || 0), 0)
    unparsedCount.value = list.filter((d) => d.status === 0).length
  } catch (e) {
    // 拉取失败保持现状
  }
}

// ---------------- 知识问答 ----------------

/** 引用来源详情抽屉 */
const sourceDrawer = ref(false)
const activeSource = ref(null)

/** 打开某条引用的片段全文 */
function openSource(s) {
  activeSource.value = s
  sourceDrawer.value = true
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (!segments.value) {
    ElMessage.warning(
      unparsedCount.value
        ? '知识库中还有待解析的文档, 请先到「知识库管理」解析入库'
        : '知识库还是空的, 请先上传并解析一份文档'
    )
    return
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  const aiMsg = { role: 'assistant', content: '', streaming: true, sources: [], sourcesOpen: false }
  messages.value.push(aiMsg)
  loading.value = true
  scrollToBottom()

  try {
    const reply = (await ragChat(text)) || {}
    aiMsg.streaming = false
    const answer = reply.answer || ''
    aiMsg.sources = reply.sources || []
    if (!answer) {
      aiMsg.content = '(空回复)'
      loading.value = false
      scrollToBottom()
      return
    }
    // 整段返回后用打字机逐字吐出, 保证阅读节奏
    let shown = 0
    const step = Math.max(1, Math.round(answer.length / 40))
    function pump() {
      shown = Math.min(answer.length, shown + step)
      aiMsg.content = answer.slice(0, shown)
      scrollToBottom()
      if (shown < answer.length) {
        setTimeout(pump, 16)
      } else {
        aiMsg.content = answer
        loading.value = false
        scrollToBottom()
      }
    }
    pump()
  } catch (e) {
    aiMsg.content = '抱歉, 请求失败: ' + (e.message || '未知错误')
    aiMsg.streaming = false
    loading.value = false
    scrollToBottom()
  }
}

function handleClear() {
  messages.value = []
  scrollToBottom()
}

onMounted(() => {
  loadKnowledgeStatus()
})
</script>

<style scoped>
.rag-chat-page {
  height: 100%;
  display: flex;
}

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

.chat-dot.off {
  background: #e6a23c;
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
  padding: 60px 0;
  text-align: center;
}

.chat-empty-icon {
  font-size: 44px;
  color: #c0c4cc;
}

.chat-empty-title {
  margin-top: 10px;
  font-size: 15px;
  font-weight: 600;
  color: #606266;
}

.chat-empty-desc {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

.chat-empty-hint {
  margin-top: 12px;
  font-size: 12px;
  color: #c0c4cc;
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

/* AI 消息块: 气泡 + 引用来源条纵向排列 */
.ai-block {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.msg-row.assistant .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 2px;
}

/* ---------- 引用来源折叠条 ---------- */
.sources-box {
  width: 100%;
  max-width: 560px;
}

.sources-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  font-size: 12px;
  color: #2d6cdf;
  background: #f0f6ff;
  border: 1px solid #d8e6ff;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.sources-toggle:hover {
  background: #e2eeff;
}

.toggle-arrow {
  transition: transform 0.2s;
}

.toggle-arrow.open {
  transform: rotate(180deg);
}

.sources-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.source-item {
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.source-item:hover {
  border-color: #2d6cdf;
  box-shadow: 0 1px 6px rgba(45, 108, 223, 0.12);
}

.source-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.source-idx {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: #eef4ff;
  color: #2d6cdf;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.source-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-score {
  flex-shrink: 0;
  font-size: 11px;
  color: #67c23a;
  background: #f0f9eb;
  padding: 1px 7px;
  border-radius: 8px;
}

.source-snippet {
  margin-top: 5px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 抽屉里的片段全文 */
.drawer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.drawer-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}

.msg-row.user .msg-bubble {
  background: #2d6cdf;
  color: #fff;
  border-top-right-radius: 2px;
}

/* ---------- 思考中占位(等待 AI 首字输出) ---------- */
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
}

.thinking-label {
  font-size: 13px;
  color: #909399;
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2d6cdf;
  animation: dot-jump 1.2s ease-in-out infinite;
}

.thinking-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.thinking-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes dot-jump {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
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
/* v-html 注入的 markdown 元素不在 scoped 作用域内, 排版样式必须放这里(全局);
   与 ChatView.vue 的同名规则一致(路由懒加载, 两个页面各自带一份保证直达时也有样式) */
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

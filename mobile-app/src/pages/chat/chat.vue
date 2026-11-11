<template>
  <view class="page">
    <!-- 自定义导航栏 -->
    <view class="nav">
      <view class="nav-brand">
        <view class="nav-logo">L</view>
        <text class="nav-title">Lion 助手</text>
      </view>
      <view class="nav-history" @click="openSheet">
        <text class="nav-history-icon">≡</text>
        <text class="nav-history-text">会话</text>
      </view>
    </view>

    <!-- 消息流 -->
    <scroll-view class="body" scroll-y :scroll-top="scrollTop" :scroll-with-animation="true">
      <!-- 欢迎引导(无消息时) -->
      <view v-if="messages.length === 0" class="hero">
        <view class="hero-avatar">L</view>
        <text class="hero-title">你好, 我是 Lion 助手</text>
        <text class="hero-sub">支持联网工具 / 多轮记忆 / 流式回答, 试试这些:</text>
        <view class="hero-chips">
          <view
            v-for="q in suggestions"
            :key="q"
            class="chip"
            @click="onSend(q)"
          >
            {{ q }}
          </view>
        </view>
      </view>

      <view
        v-for="(msg, i) in messages"
        :key="i"
        class="msg-row"
        :class="msg.role"
      >
        <view v-if="msg.role === 'assistant'" class="avatar ai">L</view>
        <view class="bubble" :class="msg.role">
          <text>{{ msg.text }}<text v-if="!msg.done && msg.role === 'assistant'" class="cursor">▌</text></text>
        </view>
        <view v-if="msg.role === 'user'" class="avatar user">
          {{ (user.nickname || '我').charAt(0) }}
        </view>
      </view>
      <view class="body-tail" />
    </scroll-view>

    <!-- 输入区 -->
    <view class="input-bar">
      <input
        v-model="input"
        class="input"
        placeholder="输入消息..."
        confirm-type="send"
        :disabled="sending"
        @confirm="onSend()"
      />
      <button
        v-if="!sending"
        class="send-btn"
        :class="{ ready: input.trim() }"
        :disabled="!input.trim()"
        @click="onSend()"
      >
        发送
      </button>
      <button v-else class="send-btn stop" @click="onStop">停止</button>
    </view>

    <!-- 会话抽屉(底部弹出) -->
    <view v-if="showSheet" class="mask" @click="showSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">会话记录</text>
          <text class="sheet-close" @click="showSheet = false">✕</text>
        </view>
        <view class="sheet-new" @click="onNewConversation">
          <text class="sheet-new-plus">＋</text>
          <text>新建会话</text>
        </view>
        <scroll-view class="sheet-list" scroll-y>
          <view
            v-for="c in conversations"
            :key="c.id"
            class="sheet-item"
            :class="{ active: c.id === currentId }"
            @click="onPickConversation(c)"
          >
            <text class="sheet-item-title">{{ c.title || `会话 ${c.id}` }}</text>
            <text v-if="c.id === currentId" class="sheet-item-current">当前</text>
          </view>
        </scroll-view>
        <view class="sheet-foot" @click="onClearContext">
          <text class="sheet-foot-text">清空当前会话上下文</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listConversations, createConversation, clearConversation, sendSync } from '@/api/chat'
import { streamChat } from '@/utils/stream'
import { useUserStore } from '@/stores/user'

const user = useUserStore()

// ---- 欢迎页推荐问题 ----
const suggestions = [
  '帮我写一首关于春天的诗',
  '用一句话解释什么是 RAG',
  '北京今天天气怎么样?',
  '帮我规划一份学习 AI 的路线'
]

// ---- 会话 ----
const conversations = ref([])
const currentId = ref(null)
const showSheet = ref(false)

async function loadConversations() {
  try {
    conversations.value = (await listConversations()) || []
    if (conversations.value.length > 0 && !currentId.value) {
      currentId.value = conversations.value[0].id
    }
  } catch (e) {
    uni.showToast({ title: e.message || '会话加载失败', icon: 'none' })
  }
}

function openSheet() {
  loadConversations()
  showSheet.value = true
}

function onPickConversation(c) {
  currentId.value = c.id
  // 一期不拉取历史消息(后端暂无历史消息接口), 切换会话即从空开始展示
  messages.value = []
  showSheet.value = false
}

async function onNewConversation() {
  try {
    const conv = await createConversation()
    if (conv) {
      conversations.value.unshift(conv)
      currentId.value = conv.id
    }
    messages.value = []
    showSheet.value = false
  } catch (e) {
    uni.showToast({ title: e.message || '新建会话失败', icon: 'none' })
  }
}

async function onClearContext() {
  try {
    await clearConversation(currentId.value || '')
    showSheet.value = false
    uni.showToast({ title: '上下文已清空', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}

// ---- 消息 ----
const messages = ref([])
const input = ref('')
const sending = ref(false)
const scrollTop = ref(0)
let streamCtl = null

function scrollBottom() {
  scrollTop.value = scrollTop.value + 100000
}

async function onSend(preset) {
  const text = (typeof preset === 'string' ? preset : input.value).trim()
  if (!text || sending.value) return
  if (typeof preset === 'string') input.value = ''
  else input.value = ''
  messages.value.push({ role: 'user', text })

  const ai = { role: 'assistant', text: '', done: false }
  messages.value.push(ai)
  sending.value = true
  scrollBottom()

  try {
    // H5 / 小程序 / App 统一走流式适配层; 失败自动降级为非流式整段输出
    streamCtl = streamChat({
      message: text,
      conversationId: currentId.value || '',
      onToken: (t) => {
        ai.text += t
        scrollBottom()
      },
      onDone: () => {
        ai.done = true
        sending.value = false
        streamCtl = null
        scrollBottom()
      },
      onError: async (msg) => {
        streamCtl = null
        // 降级: 用非流式接口整段兜底(典型场景: 低版本基础库不支持 chunked)
        try {
          const reply = await sendSync({ message: text, conversationId: currentId.value || '' })
          ai.text += (ai.text ? '\n' : '') + ((reply && reply.reply) || JSON.stringify(reply))
        } catch (e2) {
          ai.text += (ai.text ? '\n' : '') + `[出错了: ${e2.message || msg}]`
        }
        ai.done = true
        sending.value = false
        scrollBottom()
      }
    })
  } catch (e) {
    ai.text += `[出错了: ${e.message}]`
    ai.done = true
    sending.value = false
  }
}

function onStop() {
  if (streamCtl) {
    streamCtl.abort()
    streamCtl = null
  }
  const ai = messages.value[messages.value.length - 1]
  if (ai && ai.role === 'assistant') {
    ai.text += (ai.text ? '\n' : '') + '[已停止]'
    ai.done = true
  }
  sending.value = false
}

onMounted(() => {
  if (!user.isLogin) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loadConversations()
})
</script>

<style scoped>
.page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f3f5f9;
}

/* ---------- 自定义导航 ---------- */
.nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(var(--status-bar-height) + 8px) 14px 8px;
  background: #fff;
  border-bottom: 1px solid #eef0f4;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 9px;
}

.nav-logo {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-title {
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
}

.nav-history {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 16px;
  background: #f0f3fa;
}

.nav-history-icon {
  color: #2d4a8f;
  font-size: 15px;
  font-weight: 700;
}

.nav-history-text {
  color: #2d4a8f;
  font-size: 13px;
}

/* #ifdef MP-WEIXIN */
.nav-history {
  margin-right: 90px; /* 避开小程序右上角胶囊按钮 */
}
/* #endif */

/* ---------- 消息流 ---------- */
.body {
  flex: 1;
  padding: 14px;
  box-sizing: border-box;
}

/* 欢迎引导 */
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 9vh;
}

.hero-avatar {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  color: #fff;
  font-size: 30px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 20px rgba(45, 74, 143, 0.25);
}

.hero-title {
  margin-top: 16px;
  font-size: 19px;
  font-weight: 600;
  color: #1f2937;
}

.hero-sub {
  margin-top: 8px;
  font-size: 13px;
  color: #6b7280;
}

.hero-chips {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  padding: 0 6px;
}

.chip {
  padding: 9px 14px;
  background: #fff;
  border: 1px solid #e5e9f2;
  border-radius: 18px;
  font-size: 13px;
  color: #4b5563;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.chip:active {
  background: #eef2fb;
  border-color: #c8d5ef;
}

/* 消息气泡 */
.msg-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 16px;
}

.msg-row.user {
  justify-content: flex-end;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 700;
}

.avatar.ai {
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  color: #fff;
  margin-right: 9px;
}

.avatar.user {
  background: linear-gradient(135deg, #64748b, #475569);
  color: #fff;
  margin-left: 9px;
}

.bubble {
  max-width: 72%;
  border-radius: 16px;
  padding: 11px 14px;
  font-size: 15px;
  line-height: 1.65;
  word-break: break-all;
  white-space: pre-wrap;
}

.bubble.assistant {
  background: #fff;
  color: #1f2937;
  border-top-left-radius: 5px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.06);
}

.bubble.user {
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  color: #fff;
  border-top-right-radius: 5px;
  box-shadow: 0 2px 6px rgba(45, 74, 143, 0.2);
}

.cursor {
  color: #3e6cc7;
  animation: blink 1s infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.body-tail {
  height: 10px;
}

/* ---------- 输入区 ---------- */
.input-bar {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #eef0f4;
}

.input {
  flex: 1;
  height: 40px;
  background: #f3f5f9;
  border-radius: 20px;
  padding: 0 16px;
  font-size: 15px;
}

.send-btn {
  margin: 0;
  height: 40px;
  line-height: 40px;
  padding: 0 18px;
  background: #c3cfe6;
  color: #fff;
  font-size: 15px;
  border-radius: 20px;
}

.send-btn.ready {
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  box-shadow: 0 2px 8px rgba(45, 74, 143, 0.3);
}

.send-btn.stop {
  background: #e5484d;
}

/* ---------- 会话抽屉 ---------- */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  z-index: 999;
  display: flex;
  align-items: flex-end;
}

.sheet {
  width: 100%;
  background: #fff;
  border-radius: 20px 20px 0 0;
  padding-bottom: env(safe-area-inset-bottom);
  animation: slide-up 0.22s ease-out;
}

@keyframes slide-up {
  from { transform: translateY(40px); opacity: 0.6; }
  to { transform: translateY(0); opacity: 1; }
}

.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 18px 6px;
}

.sheet-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.sheet-close {
  color: #9ca3af;
  font-size: 16px;
  padding: 4px 8px;
}

.sheet-new {
  margin: 10px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #eef2fb;
  color: #2d4a8f;
  font-size: 14px;
  font-weight: 500;
}

.sheet-new-plus {
  font-size: 16px;
  font-weight: 700;
}

.sheet-list {
  max-height: 46vh;
  padding: 0 14px;
  box-sizing: border-box;
}

.sheet-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 12px;
  border-radius: 12px;
  font-size: 14px;
  color: #374151;
}

.sheet-item:active {
  background: #f5f7fa;
}

.sheet-item.active {
  background: #f0f3fa;
}

.sheet-item-title {
  max-width: 75%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sheet-item-current {
  font-size: 11px;
  color: #2d4a8f;
  background: #dde6f7;
  border-radius: 10px;
  padding: 2px 8px;
}

.sheet-foot {
  padding: 14px 18px;
  border-top: 1px solid #f0f2f5;
  margin-top: 8px;
}

.sheet-foot-text {
  font-size: 13px;
  color: #e5484d;
}
</style>

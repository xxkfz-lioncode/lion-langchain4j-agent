import request from './request'

const TOKEN_KEY = 'lion-satoken'

// ==================== 会话管理 ====================

/** 当前用户的会话列表 */
export const listConversations = () => request.get('/api/chat/conversations')

/** 新建会话 */
export const createConversation = () => request.post('/api/chat/conversations')

/** 删除会话 */
export const deleteConversation = (conversationId) =>
  request.delete(`/api/chat/conversations/${conversationId}`)

/** 某会话的历史消息 */
export const listMessages = (conversationId) =>
  request.get('/api/chat/messages', { params: { conversationId } })

// ==================== 对话 ====================

/** 发送消息, 返回 AI 回复 { reply }(阻塞式, 兼容保留) */
export const sendMessage = (message, conversationId) =>
  request.post('/api/chat/send', { message, conversationId })

/**
 * 流式对话(SSE): 用 fetch 读取 /api/chat/stream 事件流,
 * 千问每生成一个 token 即回调 onToken(token), 结束时回调 onDone()。
 *
 * @param {string} message        用户消息
 * @param {number} conversationId 会话id
 * @param {{ onToken?: (token: string) => void, onDone?: () => void }} handlers
 */
export async function chatStream(message, conversationId, { onToken, onDone } = {}) {
  const res = await fetch(
    `/api/chat/stream?message=${encodeURIComponent(message)}&conversationId=${conversationId}`,
    { headers: { satoken: localStorage.getItem(TOKEN_KEY) || '' } }
  )
  // 401: 登录态失效, 清理本地凭证并回登录页(与 request.js 行为一致)
  if (res.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem('lion-nickname')
    window.location.href = '/auth/login'
    throw new Error('登录已过期, 请重新登录')
  }
  if (!res.ok || !res.body) {
    throw new Error('请求失败(' + res.status + ')')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    // SSE 事件按 \n\n 分隔; 同一事件内多行 data: 按 SSE 规范用 \n 拼接。
    // Spring 的 SseEmitter 会把 token 里的换行拆成多行 data: 写出,
    // 若把每行 data: 当成独立 token, 换行就丢了(表现为回复不换行)。
    // 注意: Spring 写出格式是 "data:" + token(冒号后无空格), 所以直接 slice(5),
    // 不能 trim/去前导空格 —— 模型会把空格单独切成一个 token, 一去空格就丢了,
    // markdown 列表的 "- 文字" 会退化成 "-文字" 而不被识别为列表。
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const event = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const data = event.split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5))
        .join('\n')
      if (data.trim() === '[DONE]') {
        onDone?.()
        return
      }
      if (data) onToken?.(data)
    }
  }
  onDone?.()
}

/** 清空指定会话的上下文 */
export const clearChat = (conversationId) =>
  request.post('/api/chat/clear', null, { params: { conversationId } })

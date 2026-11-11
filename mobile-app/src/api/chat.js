import { request } from '@/utils/request'

// ==================== 会话管理 ====================

/** 当前用户会话列表 */
export const listConversations = () =>
  request({ url: '/api/chat/conversations', method: 'GET' })

/** 新建会话 */
export const createConversation = () =>
  request({ url: '/api/chat/conversations', method: 'POST' })

/** 清空指定会话上下文(不传则清当前默认会话) */
export const clearConversation = (conversationId) => {
  const qs = conversationId ? `?conversationId=${conversationId}` : ''
  return request({ url: `/api/chat/clear${qs}`, method: 'POST' })
}

// ==================== 对话 ====================

/** 非流式对话(整段返回, 流式不可用时的兜底) */
export const sendSync = (data) =>
  request({ url: '/api/chat/send', method: 'POST', data })

import request from './request'
import { useAuthStore } from '@/stores/auth'

/**
 * MCP 调试联调 API。
 * <p>
 * 后端 controller: {@code com.lion.agent.controller.McpController}(路径 {@code /api/mcp/**}),
 * 需登录, 鉴权由 Sa-Token 拦截器统一处理。
 */

// ====================== 连接信息 ======================

/**
 * 读取 MCP 连接信息: enabled / url / clientKey / instructions / toolCount。
 * <p>
 * 用于页面顶部状态栏; 即使 MCP 未启用也能调通(返回 toolCount=-1)。
 */
export const getMcpInfo = () => request.get('/api/mcp/info')

// ====================== 工具列表 ======================

/**
 * 列出 MCP 服务端暴露的全部工具: [{ name, description, parameters }, ...]。
 * <p>
 * parameters 字段是完整的 JSON Schema 对象(由 Hutool 在后端还原), 可直接喂给 JSON Viewer。
 */
export const listMcpTools = () => request.get('/api/mcp/tools')

/**
 * 单个工具的参数 Schema, 按名查询。name 需要 URL 编码(浏览器 fetch 会自动处理)。
 */
export const getMcpTool = (name) => request.get(`/api/mcp/tools/${encodeURIComponent(name)}`)

// ====================== 对话(模型侧) ======================

/**
 * 阻塞对话: 让 ManualAssistant 处理消息, 模型可同时调用编程式工具 / Skills / MCP 工具。
 * <p>
 * 响应: {@code { memoryId, reply }}
 *
 * @param {string} message 用户消息
 * @param {string} [memoryId] 上下文隔离 id, 留空按登录用户隔离
 */
export const mcpChat = (message, memoryId) =>
  request.post('/api/mcp/chat', { message, memoryId })

/**
 * SSE 流式对话: 返回原生 axios response, 由调用方处理 ReadableStream。
 * <p>
 * 与 {@code /api/chat/stream} 用法一致: 每行一个 {@code data: <token>}, 结束发 {@code data: [DONE]}。
 *
 * @param {string} message 用户消息(URL 编码后放在 query)
 * @param {string} [memoryId] 上下文隔离 id
 * @param {object} callbacks { onToken, onDone, onError }
 */
export function mcpChatStream(message, memoryId, { onToken, onDone, onError } = {}) {
  const url = `/api/mcp/chat/stream?message=${encodeURIComponent(message)}` +
    (memoryId ? `&memoryId=${encodeURIComponent(memoryId)}` : '')
  // 用原生 fetch 走 SSE: axios 的 fetch adapter 对流式响应处理繁琐,
  // 这里手动读 ReadableStream, 由 token-by-token 的 callback 推给上层
  const baseURL = request.defaults.baseURL || ''
  const auth = useAuthStore()
  const headers = { Accept: 'text/event-stream' }
  if (auth.token) headers.satoken = auth.token
  return fetch(baseURL + url, { method: 'GET', headers }).then(async (resp) => {
    if (!resp.ok || !resp.body) {
      const txt = await resp.text().catch(() => '')
      throw new Error(`SSE 连接失败: ${resp.status} ${txt}`)
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      // SSE 事件按 \n\n 分隔, 单条事件内多行 data: 用 \n 分隔
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const event = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        const lines = event.split('\n').filter((l) => l.startsWith('data:'))
        for (const line of lines) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') {
            onDone && onDone()
            return
          }
          if (data) onToken && onToken(data)
        }
      }
    }
    // 流自然结束但没收到 [DONE], 兜底触发 onDone
    onDone && onDone()
  }).catch((e) => {
    onError && onError(e)
    throw e
  })
}
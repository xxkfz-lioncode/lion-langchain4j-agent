import config from './config'
import { getToken } from './request'

/**
 * 流式对话统一适配层(平台差异集中在此, 页面代码零感知)。
 *
 * 后端: GET /api/chat/stream?message=&conversationId= (text/event-stream),
 *       每个 data: 行推送一个 token, data: [DONE] 表示结束。
 *
 * 平台策略(条件编译):
 * - H5        : fetch + ReadableStream 逐块读取
 * - 小程序/App : uni.request { enableChunked: true } + onChunkReceived 逐块回调
 *               (微信基础库 2.20.1+ / HBuilderX 3.6.8+)
 *
 * @returns {{ abort: Function }} 返回控制器, 调 abort() 立即中断本次流
 */
export function streamChat({ message, conversationId, onToken, onDone, onError }) {
  const url =
    `${config.BASE_URL}/api/chat/stream?message=${encodeURIComponent(message)}` +
    (conversationId ? `&conversationId=${conversationId}` : '')
  const header = { 'sa-token': getToken() }

  let aborted = false
  const controller = { abort: () => {} }

  // 按 SSE 协议逐行解析; 返回 true 表示已收到 [DONE](流结束)
  function feed(text) {
    if (!text) return false
    const lines = text.split('\n')
    for (const raw of lines) {
      const line = raw.trim()
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (data === '[DONE]') return true
      if (data) onToken && onToken(data)
    }
    return false
  }

  // ArrayBuffer → UTF-8 字符串(小程序/App 的 onChunkReceived 回调给的是 ArrayBuffer;
  // 部分基础库无 TextDecoder, 提供手写兜底解码)
  function decode(chunk) {
    if (typeof chunk === 'string') return chunk
    const bytes = new Uint8Array(chunk)
    try {
      if (typeof TextDecoder !== 'undefined') {
        return new TextDecoder('utf-8').decode(bytes)
      }
    } catch (e) { /* 走兜底 */ }
    // 手写 UTF-8 解码(兼容小程序): 处理多字节序列, 不完整字节留待下一块
    let out = ''
    for (let i = 0; i < bytes.length; ) {
      const b = bytes[i]
      if (b < 0x80) { out += String.fromCharCode(b); i += 1 }
      else if (b < 0xe0) { out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f)); i += 2 }
      else if (b < 0xf0) {
        out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f))
        i += 3
      } else {
        const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)
        i += 4
        // 代理对(>0xFFFF 的字符, 如 emoji)
        out += String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff))
      }
    }
    return out
  }

  // #ifdef H5
  ;(async () => {
    try {
      const resp = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'text/event-stream', ...header }
      })
      if (!resp.ok || !resp.body) {
        throw new Error(`SSE 连接失败(HTTP ${resp.status})`)
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      controller.abort = () => { aborted = true; try { reader.cancel() } catch (e) { /* 忽略 */ } }
      while (!aborted) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        const idx = buf.lastIndexOf('\n')
        if (idx >= 0) {
          const ready = buf.slice(0, idx + 1)
          buf = buf.slice(idx + 1)
          if (feed(ready)) { onDone && onDone(); return }
        }
      }
      if (!aborted) {
        if (feed(buf)) { onDone && onDone() } else { onDone && onDone() }
      }
    } catch (e) {
      if (!aborted) onError && onError(e.message || '流式连接失败')
    }
  })()
  // #endif

  // #ifndef H5
  const task = uni.request({
    url,
    method: 'GET',
    header,
    enableChunked: true,
    fail: (err) => {
      if (!aborted) onError && onError(err.errMsg || '流式连接失败')
    }
  })
  controller.abort = () => { aborted = true; try { task.abort() } catch (e) { /* 忽略 */ } }
  task.onChunkReceived((res) => {
    if (aborted) return
    try {
      if (feed(decode(res.data))) {
        aborted = true
        onDone && onDone()
      }
    } catch (e) {
      aborted = true
      onError && onError('响应解析失败: ' + e.message)
    }
  })
  // #endif

  return controller
}

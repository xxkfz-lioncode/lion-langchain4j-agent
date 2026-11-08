import request from './request'

const TOKEN_KEY = 'lion-satoken'

function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/**
 * 解析 SSE 事件流。
 * 后端约定: 每条 data: 后跟一个 JSON, 形如 {type: progress|sql|result|error, ...},
 * 最后推一条 data: [DONE]。
 *
 * @param {Response} res      fetch Response
 * @param {(event: object) => void} onEvent  收到每个事件对象时回调
 */
async function parseSse(res, onEvent) {
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

    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const event = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      const data = event
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5))
        .join('\n')
        .trim()
      if (!data) continue
      if (data === '[DONE]') return
      try {
        onEvent?.(JSON.parse(data))
      } catch (e) {
        // 忽略无法解析的帧, 避免单帧异常中断整条流
      }
    }
  }
}

/**
 * 流式问数。
 *
 * @param {string} question
 * @param {number|null} conversationId
 * @param {(event: object) => void} onEvent  事件对象, 见组件注释
 */
export function streamQuery(question, conversationId, onEvent) {
  const url =
    '/api/text2sql/stream?question=' + encodeURIComponent(question) +
    (conversationId ? '&conversationId=' + conversationId : '')
  return fetch(url, {
    headers: { Accept: 'text/event-stream', satoken: getToken() }
  }).then((res) => parseSse(res, onEvent))
}

/** 同步问数(兜底 / 导出) */
export const querySync = (data) =>
  request.post('/api/text2sql/query', data)

/** 问数流程步骤清单(提问前预渲染步骤条, 与后端编排同一数据源) */
export const listPipelineSteps = () =>
  request.get('/api/text2sql/pipeline/steps')

/** 表列表(keyword 可空) */
export const listTables = (keyword) =>
  request.get('/api/text2sql/schema/tables', { params: { keyword } })

/** 字段列表 */
export const listColumns = (tableName) =>
  request.get('/api/text2sql/schema/columns', { params: { tableName } })

/** JOIN 路径推断 */
export const joinPath = (from, to) =>
  request.get('/api/text2sql/schema/join-path', { params: { from, to } })

/** 刷新元数据(需管理员) */
export const refreshSchema = () => request.post('/api/text2sql/schema/refresh')

/** 提交反馈 */
export const submitFeedback = (data) =>
  request.post('/api/text2sql/feedback', data)

/** bad case 列表 */
export const listBadCases = (page = 1, size = 20) =>
  request.get('/api/text2sql/feedback/bad-cases', { params: { page, size } })

/** 推进反馈处理状态: status=HANDLED(可沉淀示例)/IGNORED */
export const handleFeedback = (feedbackId, status = 'HANDLED', promoteToFewshot = false) =>
  request.post('/api/text2sql/feedback/handle', null, {
    params: { feedbackId, status, promoteToFewshot }
  })

// ---- 数据管理: 各表独立接口(问数管理页专用) ----

/** 脱敏规则: 分页 */
export const listMaskRules = (params) => request.get('/api/text2sql/mask-rules', { params })
/** 脱敏规则: 新增 */
export const createMaskRule = (data) => request.post('/api/text2sql/mask-rules', data)
/** 脱敏规则: 更新 */
export const updateMaskRule = (id, data) => request.put(`/api/text2sql/mask-rules/${id}`, data)
/** 脱敏规则: 删除 */
export const deleteMaskRule = (id) => request.delete(`/api/text2sql/mask-rules/${id}`)

/** 术语: 分页 */
export const listTerms = (params) => request.get('/api/text2sql/terms', { params })
export const createTerm = (data) => request.post('/api/text2sql/terms', data)
export const updateTerm = (id, data) => request.put(`/api/text2sql/terms/${id}`, data)
export const deleteTerm = (id) => request.delete(`/api/text2sql/terms/${id}`)

/** Few-shot 示例: 分页 */
export const listFewshots = (params) => request.get('/api/text2sql/fewshots', { params })
export const createFewshot = (data) => request.post('/api/text2sql/fewshots', data)
export const updateFewshot = (id, data) => request.put(`/api/text2sql/fewshots/${id}`, data)
export const deleteFewshot = (id) => request.delete(`/api/text2sql/fewshots/${id}`)

/** 订单数据(只读) */
export const listOrders = (params) => request.get('/api/text2sql/orders', { params })

/** 问数日志(只读) */
export const listQueryLogs = (params) => request.get('/api/text2sql/logs', { params })

/** 反馈记录(列表; 处理动作复用 handleFeedback) */
export const listFeedback = (params) => request.get('/api/text2sql/feedback/list', { params })

import request from './request'

// ====================== 元信息 ======================

/**
 * 列出已装配的结构化输出方式(key / 中文名 / 描述)
 * 供页面 banner 渲染, 实际调用时也可以不拉(前端维护了同一份元信息, 减少一次请求)
 */
export const getStrategies = () => request.get('/api/structured/strategies')

// ====================== 单方式抽取 ======================

/**
 * 按指定方式抽取结构化对象
 * @param {string} text 待抽取文本
 * @param {'JSON_SCHEMA'|'JSON_MODE'|'PROMPT_ONLY'} strategy
 * @returns {Promise<MovieReview>}
 */
export const extract = (text, strategy) =>
  request.post('/api/structured/extract', { text, strategy })

// ====================== 自动降级 ======================

/**
 * 自动降级抽取(JSON Schema -> JSON Mode -> 纯提示词)
 * 返回 { usedStrategy, value, failures[] }
 */
export const autoExtract = (text) =>
  request.post('/api/structured/auto', { text })

// ====================== 三种方式对照 ======================

/**
 * 三种方式对照抽取: 返回 results[策略名] = { success, value|error, costMillis }
 */
export const compare = (text) =>
  request.post('/api/structured/compare', { text })
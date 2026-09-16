import request from './request'

// ==================== 通用分类 ====================

/**
 * 单条情感分类
 * @param {string} text
 * @param {'llm'|'embedding'|'both'} [mode='llm']
 */
export const classify = (text, mode = 'llm') =>
  request.get('/api/sentiment/classify', { params: { text, mode } })

/** 批量情感分类(上限 50 条) */
export const batchClassify = (texts) =>
  request.post('/api/sentiment/batch', { texts })

// ==================== 场景一: 客户反馈分析 ====================

/**
 * 客户反馈分析: 输入多条评论, 返回分布计数 + Top 褒贬词 + 详细分类
 * @param {string[]} texts
 */
export const analyzeFeedback = (texts) =>
  request.post('/api/sentiment/feedback/analyze', { texts })

// ==================== 场景二: 社交媒体监控 ====================

/**
 * 社交媒体监控: 单条社媒文本分析
 * @param {string} text
 * @param {string} [platform] 平台标签(微博/小红书/抖音...), 仅作展示用
 */
export const analyzeSocial = (text, platform) =>
  request.post('/api/sentiment/social/analyze', { text, platform })

// ==================== 场景三: 聊天机器人响应 ====================

/**
 * 聊天机器人响应建议: 输入当前用户消息 + 对话历史, 返回情感 + 建议语气 + 模板回复
 * @param {string} message
 * @param {{role:string,content:string}[]} [history]
 */
export const chatReply = (message, history = []) =>
  request.post('/api/sentiment/chat/reply', { message, history })

// ==================== 场景描述 ====================

/** 三个使用场景的标题与说明(页面顶部 banner) */
export const getScenarios = () => request.get('/api/sentiment/scenarios')
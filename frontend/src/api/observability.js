import request from './request'

/**
 * LLM 调用日志(可观测性)接口
 * 后端: LlmLogController(/api/log/llm), 数据来自 LoggingChatModelListener 异步落库的 llm_call_log
 */

/** 分页查询调用日志; 普通用户服务端强制只返回本人, admin 可查全部 */
export function pageLogs(params) {
  return request.get('/api/log/llm/page', { params })
}

/** 汇总统计: 总调用数 / 成功数 / 总Token / 平均耗时(范围同分页接口) */
export function getStats() {
  return request.get('/api/log/llm/stats')
}

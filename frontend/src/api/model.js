import request from './request'

// 模型管理(仅 admin): 页面化维护 LLM 接入配置
// key 明文永不回传(列表脱敏; 编辑留空 = 不修改)

export const listModelConfigs = (purpose) =>
  request.get('/api/model-configs', { params: { purpose: purpose || undefined } })

export const createModelConfig = (data) => request.post('/api/model-configs', data)

export const updateModelConfig = (id, data) => request.put(`/api/model-configs/${id}`, data)

export const deleteModelConfig = (id) => request.delete(`/api/model-configs/${id}`)

export const toggleModelConfig = (id, enabled) =>
  request.put(`/api/model-configs/${id}/enabled`, null, { params: { enabled } })

export const setDefaultModelConfig = (id) => request.put(`/api/model-configs/${id}/default`)

// 站点级编辑: 批量同步该站点下所有模型的 URL / 密钥
export const updateModelSite = (data) => request.put('/api/model-configs/site', data)

// 站点级删除: 批量删除该站点下所有模型(含默认模型, 业务回退系统默认)
export const deleteModelSite = (baseUrl) => request.delete('/api/model-configs/site', { params: { baseUrl } })

// 连通性测试: 真实发一次最小对话请求, 后端已放宽到 15s 模型超时, 前端给 25s 余量
export const testModelConfig = (data) => request.post('/api/model-configs/test', data, { timeout: 25000 })
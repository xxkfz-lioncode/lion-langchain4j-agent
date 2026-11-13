import request from './request'

/**
 * 上传知识文档(multipart, 仅保存不解析 — 切分入库走 parseRagDocument)
 * 返回 { id, fileName, fileSize, fileType, filePath, segmentCount: 0, status: 0, createTime }
 */
export const uploadRagFile = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/api/rag/upload', formData)
}

/**
 * 解析文档入库(切分 + 向量化, 用户显式触发; 对已解析文档重复调用 = 重新解析, 先清旧向量再重建)
 * 切分方式可选: options = { splitterType, segmentSize, overlap, pattern }
 * (只传 splitterType = 用该方式默认参数; 都不传 = 后端默认递归切分)
 * 返回 { id, fileName, fileSize, fileType, segments, total, splitterType, segmentSize, overlapSize }
 */
export const parseRagDocument = (id, options = {}) => {
  const params = new URLSearchParams()
  if (options.splitterType) params.append('splitterType', options.splitterType)
  if (options.segmentSize != null) params.append('segmentSize', options.segmentSize)
  if (options.overlap != null) params.append('overlap', options.overlap)
  if (options.pattern) params.append('pattern', options.pattern)
  const qs = params.toString()
  return request.post(`/api/rag/documents/${id}/parse${qs ? '?' + qs : ''}`)
}

/**
 * 可选切分方式列表(解析时下拉框数据源):
 * [{ code, label, shortLabel, description, defaultSegmentSize, defaultOverlap,
 *    patternSupported, defaultPattern, patternHint, minSegmentSize, maxSegmentSize }]
 */
export const getRagSplitters = () => request.get('/api/rag/splitters')

/** 知识库文档列表(按上传时间倒序): [{ id, fileName, fileSize, fileType, segmentCount, status, splitterType, segmentSize, overlapSize, createTime }] */
export const getRagDocuments = () => request.get('/api/rag/documents')

/** 删除单个文档(同时删除该文件全部向量) */
export const deleteRagDocument = (id) => request.delete(`/api/rag/documents/${id}`)

/**
 * 下载文档原文: blob 响应(拦截器返回完整 response, 调用方用 data + 保存文件名)
 * 用法: const res = await downloadRagDocument(id); URL.createObjectURL(res.data) 触发浏览器保存
 */
export const downloadRagDocument = (id) =>
  request.get(`/api/rag/documents/${id}/download`, { responseType: 'blob' })

/**
 * 知识库问答(阻塞式整段返回), 返回 { answer, sources }
 * sources: 引用来源 [{ docId, fileName, score(0-100), snippet(摘要), text(片段全文) }]
 */
export const ragChat = (question) => request.post('/api/rag/chat', { question })

/** 当前知识库片段数 */
export const getRagSize = () => request.get('/api/rag/size')

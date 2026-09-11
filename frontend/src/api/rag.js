import request from './request'

/**
 * 上传知识文档(multipart file, 追加式入库)
 * 切分方式可选: options = { splitterType, segmentSize, overlap, pattern }
 * (只传 splitterType = 用该方式默认参数; 都不传 = 后端默认递归切分)
 * 返回 { id, fileName, fileSize, fileType, segments, total, splitterType, segmentSize, overlapSize }
 */
export const uploadRagFile = (file, options = {}) => {
  const formData = new FormData()
  formData.append('file', file)
  if (options.splitterType) formData.append('splitterType', options.splitterType)
  if (options.segmentSize != null) formData.append('segmentSize', options.segmentSize)
  if (options.overlap != null) formData.append('overlap', options.overlap)
  if (options.pattern) formData.append('pattern', options.pattern)
  return request.post('/api/rag/upload', formData)
}

/**
 * 可选切分方式列表(上传下拉框数据源):
 * [{ code, label, shortLabel, description, defaultSegmentSize, defaultOverlap,
 *    patternSupported, defaultPattern, patternHint, minSegmentSize, maxSegmentSize }]
 */
export const getRagSplitters = () => request.get('/api/rag/splitters')

/** 知识库文档列表(按上传时间倒序): [{ id, fileName, fileSize, fileType, segmentCount, splitterType, segmentSize, overlapSize, createTime }] */
export const getRagDocuments = () => request.get('/api/rag/documents')

/** 删除单个文档(同时删除该文件全部向量) */
export const deleteRagDocument = (id) => request.delete(`/api/rag/documents/${id}`)

/** 知识库问答, 返回 AI 回答文本(阻塞式整段返回) */
export const ragChat = (question) => request.post('/api/rag/chat', { question })

/** 当前知识库片段数 */
export const getRagSize = () => request.get('/api/rag/size')

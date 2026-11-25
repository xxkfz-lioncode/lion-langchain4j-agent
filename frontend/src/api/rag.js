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
 * 触发解析(异步, 立即返回; 对已解析文档重复调用 = 重新解析, 先清旧向量再重建)
 * 切分方式可选: options = { splitterType, segmentSize, overlap, pattern }
 * (只传 splitterType = 用该方式默认参数; 都不传 = 后端默认递归切分)
 * 返回 { id, fileName, status: 2, progress: 0, retryCount } — 进度经 getRagDocuments 轮询获取
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

/** 知识库文档列表(按上传时间倒序): [{ id, fileName, ..., status(0待解析/1已解析/2解析中/3失败), progress, errorMsg, retryCount, parseTime, ... }] */
export const getRagDocuments = () => request.get('/api/rag/documents')

/** 删除单个文档(同时删除该文件全部向量) */
export const deleteRagDocument = (id) => request.delete(`/api/rag/documents/${id}`)

/**
 * 文档片段明细(片段抽屉): rag_segment 表数据(切分原文视角), 按片段序号升序物理分页
 * params = { page(1起), size(默认20), keyword(片段内容关键字, 可空) }
 * 返回 { total, pages, page, size, records: [{ id, seq, page, charCount, content }] }
 */
export const getRagSegments = (id, params = {}) =>
  request.get(`/api/rag/documents/${id}/segments`, { params })

/**
 * 下载文档原文: blob 响应(拦截器返回完整 response, 调用方用 data + 保存文件名)
 * 用法: const res = await downloadRagDocument(id); URL.createObjectURL(res.data) 触发浏览器保存
 */
export const downloadRagDocument = (id) =>
  request.get(`/api/rag/documents/${id}/download`, { responseType: 'blob' })

/**
 * 文档图片列表(图片资源页): rag_image 表物理分页
 * params = { page(1起), size(默认12), docId(可空), keyword(文件名关键字), onlyNoCaption(boolean) }
 * 返回 { total, pages, page, size, records: [{ id, docId, docName, fileName, page, mime,
 *        width, height, caption, objectKey, createTime }] }
 */
export const getRagImages = (params = {}) => request.get('/api/rag/images', { params })

/** 图片字节(blob): 前端 URL.createObjectURL(res.data) 生成缩略图/大图地址, 不暴露对象存储地址 */
export const getRagImageContent = (id) =>
  request.get(`/api/rag/images/${id}/content`, { responseType: 'blob' })

/**
 * 知识库问答(阻塞式整段返回), 返回 { answer, sources }
 * sources: 引用来源 [{ docId, fileName, page(PDF 页码, 文本类为 null),
 *           score(精排相关性 0-100, rerank 关闭/失败时为向量相似度), vectorScore(向量召回分, 可 null),
 *           snippet(摘要), text(片段全文) }]
 */
export const ragChat = (question) => request.post('/api/rag/chat', { question })

/** 当前知识库片段数 */
export const getRagSize = () => request.get('/api/rag/size')

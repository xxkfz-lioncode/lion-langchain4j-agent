import request from './request'

/**
 * 连接参数 { host, port, database, collection } 全部可选:
 * 不传 = 后端用 application.yml 的项目默认(langchain4j.milvus.*)
 */
function connParams(conn = {}) {
  const params = {}
  if (conn.host) params.host = conn.host
  if (conn.port) params.port = conn.port
  if (conn.database) params.database = conn.database
  if (conn.collection) params.collection = conn.collection
  return params
}

/**
 * 页面初始化: 项目默认连接 + 该连接下的数据库列表 + 集合列表
 * 返回 { defaultConnection: {host, port, database, collection}, databases: [], collections: [], connected, error? }
 */
export const getVectorConnection = () => request.get('/api/vector/connection')

/** 指定连接下的数据库列表 */
export const getVectorDatabases = (conn) =>
  request.get('/api/vector/databases', { params: connParams(conn) })

/** 指定连接/数据库下的集合列表 */
export const getVectorCollections = (conn) =>
  request.get('/api/vector/collections', { params: connParams(conn) })

/** 集合概览: { collection, fields: [], dimension, rowCount } */
export const getVectorCollectionInfo = (conn) =>
  request.get('/api/vector/collection-info', { params: connParams(conn) })

/**
 * 分页查询片段:
 * 返回 { total, offset, limit, fileName, withVector,
 *        items: [{ id, text, docId, fileName, metadata, vector?, dimension? }] }
 * withVector=true 时 vector 为 1024 维 float 数组(约 4KB/条), 流量较大按需开启
 */
export const getVectorSegments = (conn, query = {}) =>
  request.get('/api/vector/segments', {
    params: {
      ...connParams(conn),
      limit: query.limit,
      offset: query.offset,
      fileName: query.fileName || undefined,
      withVector: query.withVector
    }
  })

/** 片段总数(可按文件名过滤) */
export const getVectorCount = (conn, fileName) =>
  request.get('/api/vector/count', {
    params: { ...connParams(conn), fileName: fileName || undefined }
  })

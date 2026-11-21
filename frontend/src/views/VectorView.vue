<template>
  <div class="vector-page">
    <!-- ==================== 1. 连接配置 ==================== -->
    <div class="conn-card">
      <div class="conn-head">
        <div class="conn-title">
          <el-icon><Link /></el-icon>
          连接配置
        </div>
        <el-tag v-if="selectedConnKey === '__default__'" size="small" type="info" effect="plain" round>
          来自 application.yml (langchain4j.milvus.*)
        </el-tag>

        <!-- 连接状态 -->
        <el-tag
          class="conn-status"
          size="small"
          round
          :type="connState === 'ok' ? 'success' : connState === 'fail' ? 'danger' : 'info'"
        >
          <el-icon class="status-dot"><CircleCheckFilled v-if="connState === 'ok'" /><CircleCloseFilled v-else-if="connState === 'fail'" /><MoreFilled v-else /></el-icon>
          {{ connState === 'ok' ? '已连接' : connState === 'fail' ? '连接失败' : '未连接' }}
        </el-tag>
      </div>

      <div class="conn-form">
        <div class="conn-field">
          <span class="conn-label">连接</span>
          <!-- 连接选择器: 项目默认 / 历史连接(localStorage) / 新建; 选中即切换联动 -->
          <el-select v-model="selectedConnKey" class="conn-select" placeholder="选择连接" @change="onSelectConn">
            <el-option value="__default__" label="项目默认连接" />
            <el-option v-for="c in savedConns" :key="c.key" :value="c.key" :label="c.label">
              <div class="conn-opt">
                <span class="conn-opt-label">{{ c.label }}</span>
                <el-icon class="conn-opt-del" title="删除该历史连接" @click.stop="removeSavedConn(c)"><Delete /></el-icon>
              </div>
            </el-option>
            <el-option value="__new__" label="新建连接..." />
          </el-select>
        </div>
        <div class="conn-field">
          <span class="conn-label">地址</span>
          <el-input v-model="conn.host" size="default" class="conn-input" :disabled="locked"
                    placeholder="127.0.0.1" />
        </div>
        <div class="conn-field">
          <span class="conn-label">端口</span>
          <el-input-number v-model="conn.port" size="default" class="conn-port" :min="1" :max="65535"
                           :controls="false" :disabled="locked" />
        </div>
        <div class="conn-field">
          <span class="conn-label">数据库</span>
          <el-input v-model="conn.database" size="default" class="conn-db" :disabled="locked"
                    placeholder="default" />
        </div>
        <!-- 锁定态: 只能显式「编辑」解锁; 编辑态: 点「连接」验证并应用(成功自动存历史) -->
        <el-button v-if="locked" size="default" :icon="Lock" @click="startEdit">编辑</el-button>
        <el-button v-else size="default" type="primary" :loading="loadingStruct" @click="applyConn()">
          连接
        </el-button>
      </div>

      <div v-if="connState === 'fail'" class="conn-error">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ connError }}</span>
      </div>
      <div v-else-if="selectedConnKey === '__new__'" class="conn-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>填写地址/端口后点「连接」, 成功后自动保存到历史连接</span>
      </div>
    </div>

    <!-- ==================== 2. 主体: 左库/集合 + 右片段 ==================== -->
    <div class="main-row">
      <!-- 左: 库与集合 -->
      <aside class="side-card">
        <div class="side-sec">
          <div class="side-title">
            <span>数据库</span>
            <span class="side-count">{{ databases.length }} 个</span>
          </div>
          <el-select
            v-model="conn.database"
            class="db-select"
            size="default"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入数据库名"
            :loading="loadingStruct"
            @change="onDatabaseChange"
          >
            <el-option v-for="db in databases" :key="db" :label="db" :value="db" />
          </el-select>
        </div>

        <div class="side-sec side-sec-grow">
          <div class="side-title">
            <span>集合</span>
            <span class="side-count">{{ collections.length }} 个</span>
          </div>
          <el-scrollbar class="coll-scroll">
            <div v-if="!collections.length" class="coll-empty">
              {{ connState === 'fail' ? '未连接' : '暂无集合' }}
            </div>
            <div
              v-for="c in collections"
              :key="c.name"
              class="coll-item"
              :class="{ active: c.name === activeCollection }"
              @click="onCollectionSelect(c.name)"
            >
              <el-icon class="coll-icon"><Coin /></el-icon>
              <span class="coll-name" :title="c.name">{{ c.name }}</span>
              <!-- 片段数徽章(Attu 同款); 统计失败显示 '-' -->
              <span v-if="c.rowCount != null" class="coll-count">{{ c.rowCount }}</span>
              <span v-else class="coll-count">-</span>
            </div>
          </el-scrollbar>
        </div>
      </aside>

      <!-- 右: 片段数据 -->
      <section class="content-card">
        <!-- 集合概览 -->
        <div class="coll-head">
          <div class="coll-head-left">
            <span class="coll-head-name">{{ activeCollection || '未选择集合' }}</span>
            <template v-if="info">
              <el-tag size="small" type="primary" effect="light" round>
                {{ info.rowCount }} 个片段
              </el-tag>
              <el-tag v-if="info.dimension" size="small" type="info" effect="plain" round>
                {{ info.dimension }} 维
              </el-tag>
              <el-tag v-for="f in info.fields || []" :key="f" size="small" effect="plain" class="field-tag">
                {{ f }}
              </el-tag>
            </template>
          </div>
          <el-button size="small" :icon="RefreshRight" :loading="loading" @click="loadData">
            刷新
          </el-button>
        </div>

        <!-- 过滤条 -->
        <div class="filter-bar">
          <span class="filter-label">来源文件</span>
          <el-select
            v-model="fileName"
            class="filter-select"
            size="default"
            placeholder="全部文件(可输入完整文件名)"
            clearable
            filterable
            allow-create
            default-first-option
            @change="onFilterChange"
          >
            <el-option v-for="f in files" :key="f.id" :label="f.fileName" :value="f.fileName">
              <span class="file-option-name">{{ f.fileName }}</span>
              <span class="file-option-count">{{ f.segmentCount }} 段</span>
            </el-option>
          </el-select>

          <div class="vector-switch" title="开启后每条片段额外回传 1024 维向量本体(约 4KB/条)">
            <el-switch v-model="withVector" size="small" @change="onFilterChange" />
            <span class="vector-switch-label">显示向量</span>
          </div>

          <span class="filter-tip">
            共 {{ total }} 段 · Milvus 单次查询 offset+limit ≤ 16384
          </span>
        </div>

        <!-- 表格 -->
        <el-table
          :data="items"
          v-loading="loading"
          :border="false"
          empty-text="该集合暂无片段(或未选择集合)"
          row-key="id"
        >
          <el-table-column type="index" label="#" width="56" align="center" :index="indexFrom" />
          <el-table-column label="片段内容" min-width="380">
            <template #default="{ row }">
              <div class="seg-text" :title="row.text">{{ row.text }}</div>
            </template>
          </el-table-column>
          <el-table-column label="来源文件" width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-icon class="file-icon"><Document /></el-icon>
              {{ row.fileName || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="文档id" prop="docId" width="80" align="center">
            <template #default="{ row }">{{ row.docId || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="withVector" label="向量预览" width="190">
            <template #default="{ row }">
              <div v-if="row.vector && row.vector.length" class="vec-preview"
                   :title="'共 ' + row.vector.length + ' 维'">
                <span class="vec-dim-tag">{{ row.vector.length }}维</span>
                <span class="vec-nums">{{ vecPreview(row.vector) }}</span>
              </div>
              <span v-else class="vec-empty">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager-row">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="limit"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @current-change="loadSegments"
            @size-change="onFilterChange"
          />
        </div>
      </section>
    </div>

    <!-- ==================== 3. 详情抽屉 ==================== -->
    <el-drawer v-model="detailVisible" title="片段详情" size="480px">
      <template v-if="detail">
        <div class="detail-block">
          <div class="detail-label">片段内容</div>
          <pre class="detail-text">{{ detail.text }}</pre>
        </div>
        <div class="detail-block">
          <div class="detail-label">来源文件</div>
          <div class="detail-value">{{ detail.fileName || '—' }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">文档 id</div>
          <div class="detail-value">{{ detail.docId || '—' }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">向量主键 id</div>
          <div class="detail-value mono">{{ detail.id }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">元数据 JSON</div>
          <pre class="detail-text mono">{{ prettyMetadata }}</pre>
        </div>
        <div v-if="detail.vector && detail.vector.length" class="detail-block">
          <div class="detail-label">
            向量本体({{ detail.vector.length }} 维 · 每行 8 个数值)
          </div>
          <pre class="detail-text mono vec-full">{{ formatVector(detail.vector) }}</pre>
        </div>
        <div v-else class="detail-block">
          <div class="detail-label">向量本体</div>
          <div class="detail-value">未回传 —— 打开顶部「显示向量」开关后重新查询即可查看</div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  CircleCheckFilled, CircleCloseFilled, Coin, Delete, Document, InfoFilled,
  Link, Lock, MoreFilled, RefreshRight, WarningFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getRagDocuments } from '@/api/rag'
import {
  getVectorCollectionInfo, getVectorCollections, getVectorConnection,
  getVectorDatabases, getVectorSegments
} from '@/api/vector'

// ====================== 连接状态 ======================
// 从 application.yml 读到的项目默认连接(用于"使用默认"时的展示与回填)
const projectDefault = ref({})
// 页面上实际生效的连接(输入框始终展示当前连接参数, 可编辑后点「连接」应用)
const conn = reactive({ host: '', port: null, database: '' })
// 'ok' 已连接 / 'fail' 连接失败 / 'unknown' 未探测
const connState = ref('unknown')
const connError = ref('')

// ---- 连接管理: 历史自定义连接(localStorage 持久化, 最多 5 个) + 选中即联动 ----
const CONN_STORAGE_KEY = 'vector-milvus-conns'
const savedConns = ref(loadSavedConns())
// 当前选中的连接 key: '__default__' | '__new__' | 历史连接 key
const selectedConnKey = ref('__default__')
// 编辑态: 连接成功后参数锁定, 点「编辑」才能改(防误改导致下方数据与连接不一致)
const editing = ref(false)
/** 锁定条件: 已成功连接 + 非"新建连接"编辑中 */
const locked = computed(() => selectedConnKey.value !== '__new__' && connState.value === 'ok' && !editing.value)

/** 进入编辑态(解锁输入框, 按钮切为「连接」) */
function startEdit() {
  editing.value = true
}

function loadSavedConns() {
  try {
    // 过滤历史脏数据(host 为空的旧记录)
    return JSON.parse(localStorage.getItem(CONN_STORAGE_KEY) || '[]')
      .filter((c) => c && c.host && String(c.host).trim())
  } catch {
    return []
  }
}

/** 连接唯一标识: host:port/database(相同标识视为同一连接, 去重) */
function connKeyOf(c) {
  return `${c.host}:${c.port}/${c.database || 'default'}`
}

function persistSavedConns() {
  localStorage.setItem(CONN_STORAGE_KEY, JSON.stringify(savedConns.value.slice(0, 5)))
}

/** 下拉切换: 回填参数并自动重载(库/集合/片段全联动); 新建项清空输入框并进入编辑态 */
async function onSelectConn(key) {
  if (key === '__new__') {
    conn.host = ''
    conn.port = 19530
    conn.database = 'default'
    connState.value = 'unknown'
    connError.value = ''
    editing.value = true
    return
  }
  const c = key === '__default__'
    ? {
        host: projectDefault.value.host,
        port: projectDefault.value.port,
        database: projectDefault.value.database
      }
    : savedConns.value.find((x) => x.key === key)
  if (!c) return
  conn.host = c.host
  conn.port = c.port
  conn.database = c.database
  editing.value = false
  await applyConn(key)
}

/** 应用当前输入的连接: 重载结构(顺带连通性验证); 成功且非默认连接则存入历史并选中; 成功后重新锁定 */
async function applyConn(forcedKey) {
  // 前端必填校验: 地址为空不让发请求(否则后端回退默认主机, 造成"空地址连成功"的语义错乱)
  if (!conn.host || !String(conn.host).trim()) {
    connState.value = 'fail'
    connError.value = '请先填写 Milvus 地址'
    return
  }
  await reloadStructure()
  // 连接结果反馈: 成功/失败都给一条轻提示(失败详情在输入框下方的错误条里)
  if (connState.value !== 'ok') {
    ElMessage.error(connError.value || '连接失败')
    return // 连不上不入历史(保持解锁可重试)
  }
  ElMessage.success(`已连接 ${conn.host}:${conn.port}/${conn.database || 'default'}`)
  editing.value = false
  const key = forcedKey || connKeyOf(conn)
  if (key === '__default__') {
    selectedConnKey.value = '__default__'
    return
  }
  savedConns.value = [
    { key, label: key, host: conn.host, port: conn.port, database: conn.database },
    ...savedConns.value.filter((x) => x.key !== key)
  ].slice(0, 5)
  persistSavedConns()
  selectedConnKey.value = key
}

/** 删除历史连接; 若删的是当前选中, 回落到项目默认并联动 */
async function removeSavedConn(c) {
  savedConns.value = savedConns.value.filter((x) => x.key !== c.key)
  persistSavedConns()
  if (selectedConnKey.value === c.key) {
    selectedConnKey.value = '__default__'
    await onSelectConn('__default__')
  }
}

// ====================== 库 / 集合 ======================
const databases = ref([])
const collections = ref([])
const activeCollection = ref('')
const info = ref(null)         // { collection, rowCount, dimension, fields }
const loadingStruct = ref(false)

// ====================== 片段数据 ======================
const items = ref([])
const total = ref(0)
const page = ref(1)
const limit = ref(20)
// 支持外部联动带入: 知识库管理页片段抽屉"向量库"按钮跳转时带 ?fileName=xxx
const route = useRoute()
const fileName = ref(route.query.fileName ? String(route.query.fileName) : '')
const withVector = ref(false)
const loading = ref(false)
const files = ref([])          // 来源文件下拉(项目默认连接的已上传文档)

const indexFrom = computed(() => (page.value - 1) * limit.value + 1)

// 详情抽屉
const detailVisible = ref(false)
const detail = ref(null)
const prettyMetadata = computed(() => {
  if (!detail.value?.metadata) return '—'
  try {
    return JSON.stringify(JSON.parse(detail.value.metadata), null, 2)
  } catch {
    return detail.value.metadata
  }
})

/** 当前生效的连接参数(集合单独传, 避免和左边选择的库串味) */
function currentConn(collection) {
  return {
    host: conn.host || undefined,
    port: conn.port || undefined,
    database: conn.database || undefined,
    collection: collection || undefined
  }
}

// ====================== 初始化 ======================
onMounted(async () => {
  loadingStruct.value = true
  try {
    const data = await getVectorConnection()
    projectDefault.value = data.defaultConnection || {}
    // 默认加载当前项目的连接信息
    conn.host = projectDefault.value.host
    conn.port = projectDefault.value.port
    conn.database = projectDefault.value.database
    databases.value = data.databases || []
    collections.value = data.collections || []
    connState.value = data.connected ? 'ok' : 'fail'
    connError.value = data.error || ''
    // 默认选中项目配置里的集合(collections 已带 rowCount, 按名字匹配)
    const names = collections.value.map((c) => c.name)
    activeCollection.value = names.includes(projectDefault.value.collection)
      ? projectDefault.value.collection
      : names[0] || ''
  } catch (e) {
    connState.value = 'fail'
    connError.value = e?.message || '读取项目默认连接失败'
  } finally {
    loadingStruct.value = false
  }
  loadFiles()
  if (activeCollection.value) {
    loadData()
  }
})

async function loadFiles() {
  try {
    files.value = (await getRagDocuments()) || []
  } catch {
    files.value = []
  }
}

// ====================== 连接交互 ======================
// 连接管理见上方「连接状态」区: 下拉选中即联动(onSelectConn), 新建/改参数点「连接」应用(applyConn)

/** 重新加载结构: 库列表 + 集合列表(顺带完成一次"连接测试") */
async function reloadStructure() {
  loadingStruct.value = true
  try {
    const dbs = await getVectorDatabases(currentConn())
    databases.value = dbs || []
    if (conn.database && !databases.value.includes(conn.database)) {
      databases.value.push(conn.database)
    }
    connState.value = 'ok'
    connError.value = ''
  } catch (e) {
    connState.value = 'fail'
    connError.value = e?.message || '连接失败'
    databases.value = []
    collections.value = []
    activeCollection.value = ''
    items.value = []
    total.value = 0
    info.value = null
    loadingStruct.value = false
    return
  }
  await loadCollections(true)
  loadingStruct.value = false
  // 联动加载片段: 自动选中的集合直接带出数据(与切换数据库行为一致)
  if (activeCollection.value) {
    loadData()
  }
}

async function loadCollections(autoSelect = false) {
  try {
    const list = await getVectorCollections(currentConn())
    collections.value = list || []
    const names = collections.value.map((c) => c.name)
    if (names.length) {
      // 原集合还在就保持, 否则选第一个
      if (!activeCollection.value || !names.includes(activeCollection.value)) {
        activeCollection.value = names[0]
        if (autoSelect) fileName.value = ''
      }
    } else {
      activeCollection.value = ''
    }
  } catch (e) {
    collections.value = []
    activeCollection.value = ''
    connState.value = 'fail'
    connError.value = e?.message || '查询集合列表失败'
  }
}

async function onDatabaseChange() {
  loadingStruct.value = true
  activeCollection.value = ''
  await loadCollections(true)
  loadingStruct.value = false
  if (activeCollection.value) {
    loadData()
  }
}

function onCollectionSelect(c) {
  if (c === activeCollection.value) return
  activeCollection.value = c
  page.value = 1
  fileName.value = ''
  loadData()
}

// ====================== 数据加载 ======================
async function loadData() {
  await Promise.all([loadInfo(), loadSegments()])
}

async function loadInfo() {
  try {
    info.value = await getVectorCollectionInfo(currentConn(activeCollection.value))
  } catch {
    info.value = null
  }
}

async function loadSegments() {
  if (!activeCollection.value) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await getVectorSegments(currentConn(activeCollection.value), {
      limit: limit.value,
      offset: (page.value - 1) * limit.value,
      fileName: fileName.value,
      withVector: withVector.value
    })
    total.value = data.total || 0
    items.value = data.items || []
    connState.value = 'ok'
  } catch (e) {
    connState.value = 'fail'
    connError.value = e?.message || '查询失败'
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// 切换文件/每页条数/显示向量: 回到第一页重新查
function onFilterChange() {
  page.value = 1
  loadSegments()
}

function openDetail(row) {
  detail.value = row
  detailVisible.value = true
}

// 表格预览: 前 6 维数值(保留 4 位小数), 余下用 ... 表示
function vecPreview(vector) {
  const head = vector.slice(0, 6).map((v) => (v ?? 0).toFixed(4)).join(', ')
  return `[${head}${vector.length > 6 ? ', ...' : ''}]`
}

// 抽屉完整展示: 每行 8 个数值, 保留 6 位小数(1024 维约 128 行)
function formatVector(vector) {
  const nums = vector.map((v) => (v ?? 0).toFixed(6))
  const lines = []
  for (let i = 0; i < nums.length; i += 8) {
    const idx = `[${String(i).padStart(4, '0')}]`
    lines.push(`${idx}  ${nums.slice(i, i + 8).join(', ')}`)
  }
  return lines.join('\n')
}
</script>

<style scoped>
.vector-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ==================== 连接配置 ==================== */
.conn-card {
  background: #fff;
  border-radius: 10px;
  padding: 16px 20px;
}

.conn-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.conn-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.conn-status {
  margin-left: auto;
}

.status-dot {
  vertical-align: -2px;
  margin-right: 2px;
}

.conn-form {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.conn-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conn-label {
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}

.conn-input {
  width: 170px;
}

/* 连接选择器及下拉项(历史连接 hover 出删除) */
.conn-select {
  width: 190px;
}
.conn-opt {
  display: flex;
  align-items: center;
  gap: 8px;
}
.conn-opt-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conn-opt-del {
  color: #c0c4cc;
  visibility: hidden;
}
.conn-opt-del:hover {
  color: #f56c6c;
}
:deep(.el-select-dropdown__item):hover .conn-opt-del {
  visibility: visible;
}

.conn-port {
  width: 100px;
}

.conn-db {
  width: 150px;
}

.conn-error,
.conn-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12.5px;
  padding: 8px 10px;
  border-radius: 6px;
  line-height: 1.6;
}

.conn-error {
  color: #c45656;
  background: #fef0f0;
}

.conn-hint {
  color: #909399;
  background: #f5f7fa;
}

/* ==================== 主体两栏 ==================== */
.main-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.side-card {
  width: 264px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 520px;
}

.side-sec-grow {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.side-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.side-count {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}

.db-select {
  width: 100%;
}

.coll-scroll {
  flex: 1;
  min-height: 0;
}

.coll-empty {
  font-size: 12.5px;
  color: #c0c4cc;
  text-align: center;
  padding: 20px 0;
}

.coll-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #303133;
  transition: background 0.15s;
}

.coll-item:hover {
  background: #f5f7fa;
}

.coll-item.active {
  background: #eef2fb;
  color: #2d4a8f;
  font-weight: 600;
}

.coll-icon {
  color: #8c9ec7;
  flex-shrink: 0;
}

.coll-item.active .coll-icon {
  color: #2d4a8f;
}

.coll-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 片段数徽章(集合列表右侧, 弱化展示) */
.coll-count {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
  background: #f4f4f5;
  border-radius: 8px;
  padding: 0 7px;
  line-height: 16px;
  font-family: monospace;
}
.coll-item.active .coll-count {
  color: #2d4a8f;
  background: #e3ebfa;
}

/* ==================== 右侧内容 ==================== */
.content-card {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 10px;
  padding: 16px;
}

.coll-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid #e8eaee;
}

.coll-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.coll-head-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  font-family: Consolas, monospace;
}

.field-tag {
  font-family: Consolas, monospace;
}

/* 过滤条 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.filter-label {
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}

.filter-select {
  width: 240px;
}

.filter-tip {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.file-option-name {
  float: left;
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-option-count {
  float: right;
  font-size: 12px;
  color: #909399;
}

.vector-switch {
  display: flex;
  align-items: center;
  gap: 6px;
}

.vector-switch-label {
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}

/* 表格 */
.seg-text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
}

.file-icon {
  color: #3e6cc7;
  vertical-align: -2px;
  margin-right: 4px;
}

.vec-preview {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.vec-dim-tag {
  flex-shrink: 0;
  font-size: 11px;
  color: #2d4a8f;
  background: #eef2fb;
  border-radius: 4px;
  padding: 1px 5px;
}

.vec-nums {
  font-family: Consolas, monospace;
  font-size: 11.5px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vec-empty {
  color: #c0c4cc;
  text-align: center;
  display: block;
}

.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

/* ==================== 详情抽屉 ==================== */
.detail-block {
  margin-bottom: 18px;
}

.detail-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.detail-text {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 12px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}

.detail-text.mono,
.detail-value.mono {
  font-family: Consolas, monospace;
  font-size: 12px;
}

.vec-full {
  max-height: 360px;
  font-size: 11.5px;
  line-height: 1.6;
}

.detail-value {
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}
</style>

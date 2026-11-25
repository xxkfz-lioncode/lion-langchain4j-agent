<template>
  <div class="rag-manage-page">
    <div class="page-card">
      <!-- 顶部工具条: 上传 / 搜索 / 统计 / 刷新 -->
      <div class="toolbar">
        <div class="toolbar-left">
          <el-upload
            action="#"
            :http-request="handleUpload"
            :show-file-list="false"
            :before-upload="beforeUpload"
            accept=".txt,.md,.markdown,.pdf,.docx,.xlsx,.xls"
            multiple
            :disabled="uploading"
          >
            <el-button type="primary" :icon="UploadFilled" :loading="uploading">
              上传文档
            </el-button>
          </el-upload>
          <el-input
            v-model="keyword"
            class="search-input"
            placeholder="搜索文件名"
            clearable
            :prefix-icon="Search"
          />
        </div>
        <div class="toolbar-right">
          <span class="stat-chip">文档 {{ files.length }}</span>
          <span class="stat-chip">片段 {{ segments }}</span>
          <el-button circle :icon="Refresh" :disabled="loadingDocs" @click="loadDocuments" />
        </div>
      </div>

      <!-- 批量操作栏: 勾选文档后出现 -->
      <div v-if="selection.length" class="batch-bar">
        <span class="batch-count">已选 {{ selection.length }} 项</span>
        <div class="batch-ops">
          <el-button size="small" type="primary" @click="openParseDialog(selectionDocs)">
            解析所选
          </el-button>
          <el-button size="small" type="danger" plain @click="handleBatchDelete">
            删除所选
          </el-button>
        </div>
      </div>

      <!-- 全宽文档表格 -->
      <el-table
        :data="filteredFiles"
        v-loading="loadingDocs"
        class="doc-table"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <!-- 编号列: 生产管理页惯例放第一列(rag_document 主键, 引用溯源/向量片段/日志排错都以它为键) -->
        <el-table-column label="编号" width="80" align="center" prop="id" sortable>
          <template #default="{ row }">
            <span class="doc-id" :title="'文档编号(rag_document 主键 docId, 引用溯源/日志排错用)'">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="文档" min-width="280">
          <template #default="{ row }">
            <div class="doc-cell">
              <el-icon class="doc-icon" :class="'type-' + (row.fileType || 'txt')">
                <Document />
              </el-icon>
              <div class="doc-text">
                <div class="doc-name">{{ row.fileName }}</div>
                <div v-if="row.splitterPattern" class="doc-sub">正则 {{ row.splitterPattern }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 0" type="warning" effect="light" round>待解析</el-tag>
            <el-tooltip v-else-if="row.status === 2" content="后台解析中, 进度自动刷新" placement="top">
              <el-tag type="primary" effect="light" round>解析中 {{ row.progress ?? 0 }}%</el-tag>
            </el-tooltip>
            <el-tooltip v-else-if="row.status === 3" :content="row.errorMsg || '解析失败'" placement="top">
              <el-tag type="danger" effect="light" round>解析失败</el-tag>
            </el-tooltip>
            <el-tag v-else type="success" effect="light" round>已解析</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="片段数" width="90" align="center" prop="segmentCount" sortable>
          <template #default="{ row }">
            <!-- 已解析: 片段数可点击打开片段抽屉; 其余显示 '-' -->
            <span
              v-if="row.status === 1"
              class="seg-link"
              title="点击查看片段明细(切分结果)"
              @click="openSegments(row)"
            >{{ row.segmentCount }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100" align="center" prop="fileSize" sortable>
          <template #default="{ row }">{{ row.sizeText }}</template>
        </el-table-column>
        <el-table-column label="切分方式" width="150" align="center">
          <template #default="{ row }">
            <span v-if="row.splitterText" class="split-text" :title="row.splitterTitle">
              {{ row.splitterText }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <!-- prop 用原始 createTime(字典序即时间序); 展示走格式化的 timeText -->
        <el-table-column label="上传时间" width="160" align="center" prop="createTime" sortable>
          <template #default="{ row }">{{ row.timeText }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.status === 2"
              @click="openParseDialog([row])"
            >
              {{ row.status === 3 ? '重试解析' : row.status === 0 ? '解析' : '重新解析' }}
            </el-button>
            <el-button link type="danger" :disabled="row.status === 2" @click="handleDelete(row)">
              删除
            </el-button>
            <el-button v-if="row.filePath" link @click="handleDownload(row)">
              下载
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            description="暂无文档 — 上传后勾选并点「解析」入库, 再到「知识问答」提问"
            :image-size="90"
          />
        </template>
      </el-table>
    </div>

    <!-- 解析弹窗: 切分参数在此选择(替代原来常驻左栏的表单) -->
    <el-dialog v-model="parseDialog.visible" title="解析入库" width="540px" :close-on-click-modal="false">
      <div class="parse-targets">
        <template v-if="parseDialog.targets.length === 1">
          将解析 <b>{{ parseDialog.targets[0].fileName }}</b>
          <el-tag v-if="parseDialog.targets[0].status === 1" type="warning" size="small" effect="light">
            重新解析, 现有 {{ parseDialog.targets[0].segmentCount || 0 }} 个片段将被清除
          </el-tag>
        </template>
        <template v-else>
          将解析 <b>{{ parseDialog.targets.length }} 个文档</b>
          <el-tag v-if="reParseCountInDialog" type="warning" size="small" effect="light">
            含 {{ reParseCountInDialog }} 个已解析(旧片段将被清除重建)
          </el-tag>
        </template>
      </div>

      <div v-if="splitters.length" class="split-box">
        <div class="split-row">
          <span class="split-label">切分方式</span>
          <el-select v-model="splitForm.type" class="split-select" @change="onSplitterChange">
            <el-option v-for="s in splitters" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
        </div>
        <div class="split-desc">{{ currentSplitter && currentSplitter.description }}</div>

        <div class="split-row split-row-adv">
          <el-switch v-model="advanced" />
          <span class="split-adv-label">自定义参数</span>
          <span class="split-adv-tip">默认 {{ currentSplitter?.defaultSegmentSize ?? '-' }}/{{ currentSplitter?.defaultOverlap ?? '-' }}</span>
        </div>
        <template v-if="advanced">
          <div class="split-row">
            <span class="split-label">块大小</span>
            <el-input-number
              v-model="splitForm.segmentSize"
              size="small"
              class="split-num"
              :min="minSize"
              :max="maxSize"
            />
            <span class="split-label">重叠</span>
            <el-input-number
              v-model="splitForm.overlap"
              size="small"
              class="split-num"
              :min="0"
              :max="maxSize"
            />
          </div>
          <el-input
            v-if="currentSplitter && currentSplitter.patternSupported"
            v-model="splitForm.pattern"
            class="split-pattern"
            size="small"
            :placeholder="currentSplitter.defaultPattern"
          />
          <div v-if="currentSplitter && currentSplitter.patternHint" class="split-desc">
            {{ currentSplitter.patternHint }}
          </div>
        </template>
      </div>

      <template #footer>
        <el-button @click="parseDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="triggering" @click="confirmParse">
          开始解析
        </el-button>
      </template>
    </el-dialog>

    <!-- 片段明细抽屉: 查看切分结果(rag_segment), 支持搜索/复制/跳转向量库 -->
    <RagSegmentDrawer v-model="segDrawer.visible" :document="segDrawer.doc" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Document, Refresh, Search } from '@element-plus/icons-vue'
import {
  uploadRagFile,
  parseRagDocument,
  getRagDocuments,
  deleteRagDocument,
  downloadRagDocument,
  getRagSplitters
} from '@/api/rag'
import RagSegmentDrawer from '@/components/RagSegmentDrawer.vue'

// ---------------- 数据 ----------------

const files = ref([])
const segments = ref(0)
const keyword = ref('')
const loadingDocs = ref(false)
const uploading = ref(false)

/** 片段明细抽屉: v-model 开关 + 目标文档 */
const segDrawer = ref({ visible: false, doc: null })

/** 打开片段抽屉(仅已解析文档; 片段数列点击入口) */
function openSegments(row) {
  if (row.status !== 1) return
  segDrawer.value = { visible: true, doc: row }
}

/** 按文件名过滤(生产管理页标配) */
const filteredFiles = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return files.value
  return files.value.filter((f) => f.fileName.toLowerCase().includes(kw))
})

// ---------------- 切分方式(解析弹窗参数) ----------------

/** 后端支持的切分方式(由 SplitterType 枚举下发); 接口不可用时为空, 解析走后端默认切分 */
const splitters = ref([])
/** 解析使用的切分方式; 记住上次选择, 刷新页面不丢(仅前端偏好) */
const SPLIT_STORE_KEY = 'rag-split-preference'
const splitForm = ref({ type: 'recursive', segmentSize: null, overlap: null, pattern: '' })
/** 是否展开自定义参数(块大小 / 重叠 / 正则); 不展开时由后端取该方式默认值 */
const advanced = ref(false)
const currentSplitter = computed(() => splitters.value.find((s) => s.code === splitForm.value.type))
const minSize = computed(() => currentSplitter.value?.minSegmentSize ?? 50)
const maxSize = computed(() => currentSplitter.value?.maxSegmentSize ?? 4000)

/** 切换切分方式: 回填该方式的默认参数并记住选择 */
function onSplitterChange() {
  applySplitterDefaults()
  saveSplitPreference()
}

/** 把当前方式的默认参数回填到表单 */
function applySplitterDefaults() {
  const s = currentSplitter.value
  if (!s) return
  splitForm.value.segmentSize = s.defaultSegmentSize
  splitForm.value.overlap = s.defaultOverlap
  splitForm.value.pattern = s.patternSupported ? s.defaultPattern || '' : ''
}

/** 记住本次选择(仅前端偏好, 失败不影响解析) */
function saveSplitPreference() {
  try {
    localStorage.setItem(
      SPLIT_STORE_KEY,
      JSON.stringify({
        type: splitForm.value.type,
        advanced: advanced.value,
        segmentSize: splitForm.value.segmentSize,
        overlap: splitForm.value.overlap,
        pattern: splitForm.value.pattern
      })
    )
  } catch (e) {
    // 隐私模式等场景 localStorage 不可用, 忽略
  }
}

/** 恢复上次选择; 方式已不存在(后端下线)返回 false, 由调用方回填默认值 */
function restoreSplitPreference() {
  try {
    const raw = localStorage.getItem(SPLIT_STORE_KEY)
    if (!raw) return false
    const saved = JSON.parse(raw)
    if (!saved || !splitters.value.some((s) => s.code === saved.type)) return false
    const target = splitters.value.find((s) => s.code === saved.type)
    splitForm.value.type = saved.type
    advanced.value = !!saved.advanced
    splitForm.value.segmentSize = saved.segmentSize ?? target.defaultSegmentSize
    splitForm.value.overlap = saved.overlap ?? target.defaultOverlap
    splitForm.value.pattern =
      saved.pattern || (target.patternSupported ? target.defaultPattern || '' : '')
    return true
  } catch (e) {
    return false
  }
}

/** 组装解析参数: 未展开自定义参数时只传方式, 其余由后端取该方式默认值 */
function splitOptions() {
  const options = { splitterType: splitForm.value.type }
  if (advanced.value) {
    options.segmentSize = splitForm.value.segmentSize
    options.overlap = splitForm.value.overlap
    if (currentSplitter.value?.patternSupported) options.pattern = splitForm.value.pattern
  }
  return options
}

/** 拉取后端支持的切分方式并恢复上次选择(接口不可用则隐藏选择区, 解析走后端默认切分) */
async function loadSplitters() {
  try {
    splitters.value = (await getRagSplitters()) || []
  } catch (e) {
    splitters.value = []
    return
  }
  if (!splitters.value.length) return
  if (!restoreSplitPreference()) applySplitterDefaults()
}

// ---------------- 上传(仅保存) ----------------

const MAX_SIZE = 20 * 1024 * 1024

/** 上传前校验: 扩展名 + 大小(与后端保持一致, 尽早拦截) */
function beforeUpload(file) {
  const name = file.name || ''
  const lower = name.toLowerCase()
  const supported = /\.(txt|md|markdown|pdf|docx|xlsx|xls)$/.test(lower)
  if (!supported) {
    ElMessage.error('仅支持 .txt / .md / .pdf / .docx / .xlsx 格式的知识文档')
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.error('文件大小不能超过 20MB')
    return false
  }
  return true
}

let uploadCount = 0

/** 自定义上传: 仅保存文件不解析(解析由「解析」按钮触发); 支持多文件并行 */
async function handleUpload({ file, onSuccess, onError }) {
  uploadCount++
  uploading.value = true
  try {
    const data = await uploadRagFile(file)
    await loadDocuments()
    ElMessage.success(`「${data.fileName}」已上传, 点击「解析」入库`)
    onSuccess(data)
  } catch (e) {
    onError(e)
  } finally {
    uploadCount--
    if (uploadCount <= 0) {
      uploadCount = 0
      uploading.value = false
    }
  }
}

// ---------------- 解析(异步触发 + 轮询进度) ----------------

/** 解析任务提交中(弹窗按钮 loading) */
const triggering = ref(false)

/** 解析弹窗: targets = 待解析文档列表 */
const parseDialog = ref({ visible: false, targets: [] })

const reParseCountInDialog = computed(
  () => parseDialog.value.targets.filter((f) => f.status === 1).length
)

/** 打开解析弹窗(单条或批量入口统一走这里); 解析中的文档不可重复触发 */
function openParseDialog(targets) {
  const usable = targets.filter((f) => f.status !== 2)
  if (!usable.length) {
    ElMessage.warning('所选文档都在解析中, 请等待完成')
    return
  }
  parseDialog.value = { visible: true, targets: usable }
}

/** 弹窗确认: 逐个提交解析任务(触发即返回), 提交后启动轮询看进度 */
async function confirmParse() {
  const targets = parseDialog.value.targets
  parseDialog.value.visible = false
  saveSplitPreference()
  triggering.value = true
  let ok = 0
  let fail = 0
  try {
    for (const f of targets) {
      try {
        await parseRagDocument(f.id, splitOptions())
        ok++
      } catch (e) {
        fail++
        ElMessage.error(`「${f.fileName}」解析触发失败: ${e.message || '未知错误'}`)
      }
    }
  } finally {
    triggering.value = false
  }
  if (ok > 0) {
    ElMessage.success(`已提交 ${ok} 个解析任务, 进度将自动刷新`)
    await loadDocuments(true)
    startParsePolling()
  }
  if (fail > 0 && ok === 0) {
    ElMessage.warning('解析任务未能启动, 请稍后重试')
  }
}

// ---------------- 解析进度轮询 ----------------

/** 轮询定时器(存在"解析中"文档时每 1.5s 刷列表) */
let pollTimer = null

/** 刷新前的状态快照(用于"解析完成/失败"一次性提示) */
let statusSnapshot = {}

function startParsePolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    if (!files.value.some((f) => f.status === 2)) {
      stopParsePolling()
      return
    }
    // 记录刷新前状态 → 静默刷新 → 对状态发生迁移的文档给一次性提示
    statusSnapshot = Object.fromEntries(files.value.map((f) => [f.id, f.status]))
    await loadDocuments(true)
    for (const f of files.value) {
      const prev = statusSnapshot[f.id]
      if (prev === 2 && f.status === 1) {
        ElMessage.success(`「${f.fileName}」解析完成, 入库 ${f.segmentCount} 个片段`)
      } else if (prev === 2 && f.status === 3) {
        ElMessage.error(`「${f.fileName}」解析失败: ${f.errorMsg || '未知错误'}`)
      }
    }
  }, 1500)
}

function stopParsePolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  statusSnapshot = {}
}

// ---------------- 删除 ----------------

/** 删除文档(含其向量), 带确认弹窗 */
async function handleDelete(f) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${f.fileName}」吗? 该文件的 ${f.segmentCount || 0} 个片段将从知识库一并移除。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    await deleteRagDocument(f.id)
    ElMessage.success(`「${f.fileName}」已删除`)
    await loadDocuments()
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || '未知错误'))
  }
}

/** 批量删除勾选文档(含其向量), 统一确认后逐个执行 */
async function handleBatchDelete() {
  const targets = [...selectionDocs.value]
  if (!targets.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除所选 ${targets.length} 个文档吗? 共 ${targets.reduce((s, f) => s + (f.segmentCount || 0), 0)} 个片段将从知识库一并移除。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch (e) {
    return // 用户取消
  }
  loadingDocs.value = true
  let fail = 0
  try {
    for (const f of targets) {
      try {
        await deleteRagDocument(f.id)
      } catch (e) {
        fail++
        ElMessage.error(`「${f.fileName}」删除失败: ${e.message || '未知错误'}`)
      }
    }
  } finally {
    loadingDocs.value = false
    await loadDocuments()
  }
  if (fail === 0) ElMessage.success('所选文档已删除')
}

/** 下载文档原文: blob → 临时 URL 触发浏览器保存(文件名用原始文件名) */
async function handleDownload(row) {
  try {
    const res = await downloadRagDocument(row.id)
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = row.fileName
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('下载失败: ' + (e.message || '未知错误'))
  }
}

// ---------------- 表格勾选 ----------------

/** 当前勾选行(el-table selection 事件) */
const selection = ref([])
const selectionDocs = computed(() => selection.value)

function onSelectionChange(rows) {
  selection.value = rows
}

// ---------------- 展示辅助 ----------------

/** 切分方式编码 → 短名(优先用后端下发的短名, 接口不可用退回编码) */
function splitterShort(code) {
  return splitters.value.find((s) => s.code === code)?.shortLabel || code || '默认'
}

/**
 * 文档 → "递归 300/50": 仅已解析(status=1)才展示。
 * 待解析时 DB 列默认值(recursive/300/50)会带出来, 但那是解析时才会真正指定的参数, 显示了会误导。
 */
function splitterLabel(item) {
  if (!item || item.status !== 1 || !item.splitterType) return ''
  return `${splitterShort(item.splitterType)} ${item.segmentSize}/${item.overlapSize}`
}

/** 字节 → 可读大小(B/KB/MB) */
function formatSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

/** ISO 时间 → yyyy-MM-dd HH:mm */
function formatTimeText(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return String(t).replace('T', ' ').slice(0, 16)
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    ` ${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

/** 拉取文档列表并补充展示字段, 同时同步总片段数; silent=true 用于轮询(不闪 loading) */
async function loadDocuments(silent = false) {
  if (!silent) loadingDocs.value = true
  try {
    const list = (await getRagDocuments()) || []
    files.value = list.map((d) => ({
      ...d,
      sizeText: formatSize(d.fileSize),
      timeText: formatTimeText(d.createTime),
      splitterText: splitterLabel(d),
      splitterTitle: `切分方式: ${splitterLabel(d)}${d.splitterPattern ? ` · 正则 ${d.splitterPattern}` : ''}`
    }))
    segments.value = list.reduce((sum, d) => sum + (d.segmentCount || 0), 0)
    // 页面加载/刷新时若已有解析中的任务(如后台刚重启), 自动接续轮询
    if (list.some((d) => d.status === 2)) startParsePolling()
  } catch (e) {
    // 拉取失败不阻断页面
  } finally {
    if (!silent) loadingDocs.value = false
  }
}

onUnmounted(stopParsePolling)

onMounted(async () => {
  await loadSplitters()
  await loadDocuments()
})
</script>

<style scoped>
.rag-manage-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  padding: 16px 20px;
}

/* ---------- 工具条 ---------- */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-input {
  width: 220px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.stat-chip {
  font-size: 13px;
  color: #606266;
  background: #f4f7fd;
  border: 1px solid #e3ecfb;
  border-radius: 14px;
  padding: 4px 12px;
}

/* ---------- 批量操作栏 ---------- */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  margin-top: 12px;
  border-radius: 8px;
  background: #eef4ff;
  border: 1px solid #d3e3fd;
}

.batch-count {
  font-size: 13px;
  color: #2d6cdf;
  font-weight: 600;
}

.batch-ops {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ---------- 表格 ---------- */
.doc-table {
  margin-top: 14px;
  flex: 1;
}

.doc-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.doc-icon {
  font-size: 18px;
  flex-shrink: 0;
}

/* 编号列: 纯数字 + 等宽字体, 弱化颜色不与文件名抢视觉 */
.doc-id {
  font-size: 13px;
  color: #909399;
  font-family: monospace;
  white-space: nowrap;
}

/* 片段数链接(已解析文档可点击打开片段抽屉) */
.seg-link {
  color: #2d6cdf;
  cursor: pointer;
  text-decoration: underline dotted;
  text-underline-offset: 3px;
}
.seg-link:hover {
  color: #1d4fb8;
}

.doc-text {
  min-width: 0;
}

.doc-name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.split-text {
  color: #2d6cdf;
}

/* 文件类型图标配色: pdf 红 / txt 绿 / md 蓝 / docx 蓝 / xlsx 绿 */
.doc-icon.type-pdf {
  color: #f56c6c;
}
.doc-icon.type-txt {
  color: #67c23a;
}
.doc-icon.type-md {
  color: #2d6cdf;
}
.doc-icon.type-docx {
  color: #185abd;
}
.doc-icon.type-xlsx {
  color: #1d8a4e;
}

/* ---------- 解析弹窗 ---------- */
.parse-targets {
  font-size: 14px;
  color: #303133;
  line-height: 1.8;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.split-box {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: #f8f9fb;
  border: 1px solid #e8eaee;
}

.split-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.split-row-adv {
  margin-top: 10px;
}

.split-label {
  flex-shrink: 0;
  font-size: 13px;
  color: #606266;
}

.split-select {
  flex: 1;
  min-width: 0;
}

.split-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.split-adv-label {
  font-size: 13px;
  color: #606266;
}

.split-adv-tip {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}

.split-num {
  width: 100px;
}

.split-pattern {
  margin-top: 8px;
}
</style>

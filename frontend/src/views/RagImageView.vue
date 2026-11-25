<template>
  <div class="image-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-select v-model="docId" clearable filterable placeholder="全部文档" class="doc-select"
                     @change="onFilterChange">
            <el-option v-for="d in docs" :key="d.id" :label="d.fileName" :value="d.id" />
          </el-select>
          <el-input v-model="keyword" clearable placeholder="搜索图片文件名" class="search-input"
                    :prefix-icon="Search" @keyup.enter="onFilterChange" @clear="onFilterChange" />
          <el-switch v-model="onlyNoCaption" active-text="仅未描述" @change="onFilterChange" />
        </div>
        <div class="toolbar-right">
          <span class="stat-chip">图片 {{ total }}</span>
          <el-button circle :icon="Refresh" :loading="loading" @click="load" />
        </div>
      </div>

      <div class="page-hint">
        解析 PDF 时提取的内嵌图片，已存储于文件/对象存储并在此留底。
        图片描述（VLM 看图说话）暂未启用，当前 <b>不参与向量检索</b>，后续填充描述即可复用本页数据。
      </div>

      <el-table :data="records" v-loading="loading" stripe style="width: 100%" row-key="id">
        <el-table-column label="预览" width="110" align="center">
          <template #default="{ row }">
            <div class="thumb-box" @click="openPreview(row)">
              <img v-if="thumbs[row.id]" :src="thumbs[row.id]" class="thumb" :alt="row.fileName" />
              <div v-else class="thumb-empty">{{ thumbFailed[row.id] ? '无预览' : '加载中' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="文件名" min-width="230">
          <template #default="{ row }">
            <div class="cell-name" :title="row.objectKey">{{ row.fileName || '-' }}</div>
            <div class="cell-sub">{{ row.objectKey || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="所属文档" min-width="180">
          <template #default="{ row }">
            <span :title="row.docName">{{ row.docName || ('文档 #' + row.docId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="页码" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.page != null" size="small" effect="plain" round>第 {{ row.page }} 页</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="尺寸" width="120" align="center">
          <template #default="{ row }">{{ row.width && row.height ? `${row.width} × ${row.height}` : '-' }}</template>
        </el-table-column>
        <el-table-column label="图片描述" min-width="180">
          <template #default="{ row }">
            <span v-if="row.caption" class="cell-caption" :title="row.caption">{{ row.caption }}</span>
            <el-tag v-else size="small" type="warning" effect="light" round>待生成</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入库时间" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPreview(row)">查看大图</el-button>
            <el-button link size="small" @click="download(row)">下载</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无图片：上传并解析含图片的 PDF 后，这里会自动出现记录" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                       :page-sizes="[12, 24, 48]" layout="total, sizes, prev, pager, next"
                       background @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>

    <el-dialog v-model="previewVisible" :title="current?.fileName || '图片预览'" width="72%" top="6vh"
               destroy-on-close>
      <div class="preview-body">
        <div class="preview-img">
          <img v-if="current && thumbs[current.id]" :src="thumbs[current.id]" :alt="current.fileName" />
          <el-empty v-else description="图片加载失败或已被清理" />
        </div>
        <div v-if="current" class="preview-meta">
          <div><span>所属文档</span>{{ current.docName || ('文档 #' + current.docId) }}</div>
          <div><span>页码</span>{{ current.page != null ? '第 ' + current.page + ' 页' : '-' }}</div>
          <div><span>尺寸</span>{{ current.width }} × {{ current.height }} px</div>
          <div><span>类型</span>{{ current.mime || 'image/png' }}</div>
          <div><span>对象 Key</span>{{ current.objectKey }}</div>
          <div><span>图片描述</span>{{ current.caption || '待生成（VLM 未启用）' }}</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!current" @click="current && download(current)">下载原图</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { getRagImages, getRagImageContent, getRagDocuments } from '@/api/rag'

const docs = ref([])
const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const docId = ref(null)
const keyword = ref('')
const onlyNoCaption = ref(false)
const loading = ref(false)

/** id → blob objectURL(当前页有效, 翻页/卸载时释放, 避免内存泄漏) */
const thumbs = reactive({})
const thumbFailed = reactive({})
let loadToken = 0

const previewVisible = ref(false)
const current = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await getRagImages({
      page: page.value,
      size: size.value,
      docId: docId.value || undefined,
      keyword: keyword.value || undefined,
      onlyNoCaption: onlyNoCaption.value || undefined
    })
    records.value = data.records || []
    total.value = data.total || 0
    await loadThumbs(records.value)
  } catch (e) {
    ElMessage.error(e?.message || '图片列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 当前页缩略图: 逐张走鉴权 blob 接口 → objectURL; loadToken 防快速翻页的竞态覆盖 */
async function loadThumbs(rows) {
  const my = ++loadToken
  releaseThumbs()
  await Promise.all(rows.map(async (row) => {
    try {
      const res = await getRagImageContent(row.id)
      if (my !== loadToken) return
      thumbs[row.id] = URL.createObjectURL(res.data)
    } catch (e) {
      if (my === loadToken) thumbFailed[row.id] = true
    }
  }))
}

function releaseThumbs() {
  Object.keys(thumbs).forEach((k) => {
    URL.revokeObjectURL(thumbs[k])
    delete thumbs[k]
  })
  Object.keys(thumbFailed).forEach((k) => delete thumbFailed[k])
}

function openPreview(row) {
  current.value = row
  previewVisible.value = true
  // 缩略图已加载则大图复用同一 URL; 失败时重试一次
  if (!thumbs[row.id] && thumbFailed[row.id]) {
    getRagImageContent(row.id)
      .then((res) => { thumbs[row.id] = URL.createObjectURL(res.data); delete thumbFailed[row.id] })
      .catch(() => {})
  }
}

async function download(row) {
  try {
    const res = await getRagImageContent(row.id)
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = row.fileName || 'image.png'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('图片下载失败: 文件可能已被清理')
  }
}

function onFilterChange() {
  page.value = 1
  load()
}

function onSizeChange() {
  page.value = 1
  load()
}

function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 19)
}

onMounted(async () => {
  try {
    docs.value = (await getRagDocuments()) || []
  } catch (e) {
    docs.value = []
  }
  load()
})

onBeforeUnmount(releaseThumbs)
</script>

<style scoped>
.image-page {
  padding: 16px;
}

.page-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.05);
  padding: 16px 20px 20px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.doc-select {
  width: 240px;
}

.search-input {
  width: 220px;
}

.stat-chip {
  font-size: 13px;
  color: #606266;
  background: #f4f6fa;
  border-radius: 14px;
  padding: 5px 12px;
}

.page-hint {
  margin: 12px 0 16px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  background: #f7f9fc;
  border-left: 3px solid #2d6cdf;
  border-radius: 4px;
}

.thumb-box {
  width: 76px;
  height: 58px;
  margin: 0 auto;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
}

.thumb {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.thumb-empty {
  font-size: 12px;
  color: #c0c4cc;
}

.cell-name {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-sub {
  font-size: 11px;
  color: #a8abb2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-caption {
  font-size: 13px;
  color: #303133;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.preview-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-img {
  text-align: center;
  background: #f7f9fc;
  border-radius: 8px;
  padding: 12px;
  max-height: 62vh;
  overflow: auto;
}

.preview-img img {
  max-width: 100%;
  max-height: 58vh;
  object-fit: contain;
}

.preview-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 20px;
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}

.preview-meta span {
  display: inline-block;
  width: 78px;
  color: #909399;
}
</style>

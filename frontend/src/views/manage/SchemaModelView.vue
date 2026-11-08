<template>
  <div class="schema-view">
    <!-- 左侧: 表列表 -->
    <el-card class="table-list-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>数据模型(自动同步)</span>
          <el-tooltip content="重新扫描目标库并覆盖语义层, 需管理员">
            <el-button link type="primary" :loading="refreshing" @click="refresh">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </template>
      <el-input
        v-model="keyword"
        placeholder="搜索表名 / 注释"
        clearable
        @input="loadTables"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-empty v-if="!tableLoading && tables.length === 0" description="暂无表, 点击右上角刷新" />
      <div
        v-for="t in tables"
        :key="t.tableName"
        class="table-item"
        :class="{ active: t.tableName === currentTable }"
        @click="selectTable(t)"
      >
        <el-icon class="table-icon"><Coin /></el-icon>
        <div class="table-meta">
          <div class="table-name">
            {{ t.tableName }}
            <el-tag v-if="!t.queryable" size="small" type="danger">禁查</el-tag>
          </div>
          <div class="table-comment">{{ t.tableComment || '—' }}</div>
        </div>
        <span class="row-count">{{ formatRows(t.rowCount) }}</span>
      </div>
    </el-card>

    <!-- 右侧: 字段模型 -->
    <el-card class="table-detail-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            {{ currentTable || '请选择左侧表' }}
            <el-tag v-if="currentComment" size="small" type="info" class="comment-tag">
              {{ currentComment }}
            </el-tag>
          </span>
          <span class="hint">只读模型 · 由元数据刷新自动生成, 页面不可编辑</span>
        </div>
      </template>

      <el-table v-loading="columnLoading" :data="columns" border stripe height="100%">
        <el-table-column prop="columnName" label="字段" width="160" />
        <el-table-column prop="dataType" label="类型" width="110" />
        <el-table-column prop="columnKey" label="键" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.columnKey" size="small" :type="row.columnKey === 'PRI' ? 'warning' : 'info'">
              {{ row.columnKey }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="columnComment" label="注释" min-width="160" show-overflow-tooltip />
        <el-table-column prop="llmDescription" label="LLM 增强含义" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.llmDescription || '—' }}</template>
        </el-table-column>
        <el-table-column prop="valueDomain" label="值域/枚举" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.valueDomain || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sampleValues" label="采样值" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sampleValues || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sensitive" label="敏感" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.sensitive" size="small" type="danger">是</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Coin } from '@element-plus/icons-vue'
import { listTables, listColumns, refreshSchema } from '@/api/text2sql'

// ---- 表列表 ----
const tables = ref([])
const tableLoading = ref(false)
const refreshing = ref(false)
const keyword = ref('')

async function loadTables() {
  tableLoading.value = true
  try {
    const res = await listTables(keyword.value || undefined)
    tables.value = res || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    tableLoading.value = false
  }
}

async function refresh() {
  refreshing.value = true
  try {
    const version = await refreshSchema()
    ElMessage.success('元数据已刷新, 版本: ' + (version || '-'))
    await loadTables()
  } catch (e) {
    // 拦截器已提示
  } finally {
    refreshing.value = false
  }
}

// ---- 字段 ----
const currentTable = ref('')
const currentComment = ref('')
const columns = ref([])
const columnLoading = ref(false)

async function selectTable(t) {
  if (t.tableName === currentTable.value) return
  currentTable.value = t.tableName
  currentComment.value = t.tableComment || ''
  columnLoading.value = true
  columns.value = []
  try {
    const res = await listColumns(t.tableName)
    columns.value = res || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    columnLoading.value = false
  }
}

function formatRows(n) {
  if (n == null) return ''
  return n >= 10000 ? (n / 10000).toFixed(1) + 'w' : String(n)
}

onMounted(() => {
  loadTables().then(() => {
    // 默认选中第一张表
    if (tables.value.length > 0) selectTable(tables.value[0])
  })
})
</script>

<style scoped>
.schema-view {
  height: 100%;
  display: flex;
  gap: 12px;
}

.table-list-card {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.table-list-card :deep(.el-card__body) {
  flex: 1;
  overflow: auto;
}

.table-detail-card {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.table-detail-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.comment-tag {
  margin-left: 8px;
  font-weight: 400;
}

.hint {
  font-weight: 400;
  font-size: 12px;
  color: #909399;
}

.table-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-top: 4px;
}

.table-item:hover { background: #f5f7fa; }
.table-item.active { background: #eef2fb; }
.table-item.active .table-name { color: #2d4a8f; }

.table-icon { color: #909399; }
.table-item.active .table-icon { color: #2d4a8f; }

.table-meta { flex: 1; min-width: 0; }

.table-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.table-comment {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-count {
  font-size: 12px;
  color: #c0c4cc;
}
</style>

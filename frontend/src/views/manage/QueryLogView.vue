<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索问题 / 生成 SQL"
          clearable
          style="width: 240px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-select v-model="query.status" placeholder="结果状态" clearable style="width: 140px" @change="load(1)">
          <el-option label="SUCCESS 成功" value="SUCCESS" />
          <el-option label="EMPTY 空结果" value="EMPTY" />
          <el-option label="TIMEOUT 超时" value="TIMEOUT" />
          <el-option label="ERROR 错误" value="ERROR" />
        </el-select>
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
        <div class="spacer" />
        <span class="hint">只读留痕, 不支持编辑/删除</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-body">
              <p><b>召回表:</b> {{ row.recalledTables || '—' }}</p>
              <p><b>命中术语:</b> {{ row.termsHit || '—' }}</p>
              <p><b>生成 SQL:</b></p>
              <pre class="sql">{{ row.generatedSql || '—' }}</pre>
              <p v-if="row.rejectReason"><b>拒绝原因:</b> {{ row.rejectReason }}</p>
              <p v-if="row.errorMessage"><b>错误信息:</b> {{ row.errorMessage }}</p>

              <!-- 结果快照: 存储当时查询的全量返回行(执行时已按脱敏规则脱敏) -->
              <template v-if="snap(row)">
                <p>
                  <b>结果快照:</b>
                  <span class="snap-hint">共 {{ snap(row).rows.length }} 行, 数据在执行时已按脱敏规则脱敏</span>
                </p>
                <el-table :data="snapPageRows(row)" size="small" border max-height="280">
                  <el-table-column
                    v-for="(c, ci) in snap(row).columns"
                    :key="c"
                    :label="c"
                    min-width="120"
                    show-overflow-tooltip
                  >
                    <!-- 快照行是"值数组"(与列名数组按位置对应), 用下标取值 -->
                    <template #default="{ row: r }">{{ r[ci] ?? '' }}</template>
                  </el-table-column>
                </el-table>
                <div class="snap-pager" v-if="snap(row).rows.length > SNAP_SIZE">
                  <el-pagination
                    :current-page="snapPageMap[row.id] || 1"
                    :page-size="SNAP_SIZE"
                    :total="snap(row).rows.length"
                    layout="total, prev, pager, next"
                    @current-change="(p) => (snapPageMap[row.id] = p)"
                  />
                </div>
              </template>
              <p v-else-if="row.resultStatus === 'SUCCESS' || row.resultStatus === 'EMPTY'">
                <b>结果快照:</b> <span class="snap-hint">无(该记录产生于快照功能上线前)</span>
              </p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="intent" label="意图" width="110" />
        <el-table-column prop="sqlValid" label="SQL 校验" width="100">
          <template #default="{ row }">
            <el-tag :type="row.sqlValid === 1 ? 'success' : 'danger'">
              {{ row.sqlValid === 1 ? '通过' : '拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="resultStatus" label="结果状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.resultStatus] || 'info'">{{ row.resultStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rowCount" label="行数" width="80" align="right" />
        <el-table-column prop="executeMs" label="耗时(ms)" width="100" align="right" />
        <el-table-column prop="userId" label="用户ID" width="80" align="right" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { listQueryLogs } from '@/api/text2sql'

const STATUS_TAG = { SUCCESS: 'success', EMPTY: 'info', TIMEOUT: 'warning', ERROR: 'danger' }

// 快照分页大小(前端展示分页, 存储为全量行)
const SNAP_SIZE = 10
// 各行快照的当前页码: key = 日志行 id
const snapPageMap = reactive({})

const query = reactive({ keyword: '', status: null })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

// 解析结果快照 JSON(解析结果缓存在行对象上, 避免每次渲染重复 parse)
function snap(row) {
  if (!row.resultSnapshot) return null
  if (row._snap === undefined) {
    try {
      row._snap = JSON.parse(row.resultSnapshot)
    } catch (e) {
      row._snap = null
    }
  }
  return row._snap
}

function snapPageRows(row) {
  const s = snap(row)
  if (!s || !s.rows) return []
  const p = snapPageMap[row.id] || 1
  return s.rows.slice((p - 1) * SNAP_SIZE, p * SNAP_SIZE)
}

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listQueryLogs({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      page: page.value,
      size: size.value
    })
    // axios 拦截器已解包 R 信封, res 即 PageResult
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 12px; }
.pager { display: flex; justify-content: flex-end; padding-top: 10px; }
.expand-body { padding: 8px 16px; }
.expand-body p { margin: 4px 0; }
.sql {
  background: #f5f7fa; padding: 8px 12px; border-radius: 4px;
  font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 4px 0;
}
.snap-hint { color: #909399; font-size: 12px; font-weight: normal; }
.snap-pager { display: flex; justify-content: flex-end; padding-top: 8px; }
</style>

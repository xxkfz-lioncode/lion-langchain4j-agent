<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="query.status" placeholder="处理状态" clearable style="width: 160px" @change="load(1)">
          <el-option label="PENDING 待处理" value="PENDING" />
          <el-option label="HANDLED 已处理" value="HANDLED" />
          <el-option label="IGNORED 已忽略" value="IGNORED" />
        </el-select>
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-body">
              <p><b>用户纠正 SQL:</b></p>
              <pre class="sql">{{ row.correctSql || '—' }}</pre>
              <p><b>文字反馈:</b> {{ row.comment || '—' }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="70" />
        <!-- min-width 而非 width: 容器更宽时让该列弹性伸展, 避免右侧留白 -->
        <el-table-column prop="bizId" label="bizId" min-width="240" show-overflow-tooltip />
        <el-table-column prop="rating" label="评价" width="90">
          <template #default="{ row }">
            <el-tag :type="row.rating === 1 ? 'success' : row.rating === -1 ? 'danger' : 'info'">
              {{ row.rating === 1 ? '赞' : row.rating === -1 ? '踩' : '未评价' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="handleStatus" label="处理状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.handleStatus] || 'info'">{{ row.handleStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="反馈时间" width="165" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <template v-if="row.handleStatus === 'PENDING'">
              <div class="op-btns">
                <el-button link type="primary" @click="handle(row, true)">沉淀示例</el-button>
                <el-button link type="warning" @click="handle(row, false)">已处理</el-button>
                <el-button link type="info" @click="ignore(row)">忽略</el-button>
              </div>
            </template>
            <span v-else class="done-text">已处理</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { listFeedback, handleFeedback } from '@/api/text2sql'

const STATUS_TAG = { PENDING: 'warning', HANDLED: 'success', IGNORED: 'info' }

const query = reactive({ status: null })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listFeedback({
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

/** 标记已处理; promoteToFewshot=true 时把纠正 SQL 沉淀为 few-shot 示例 */
async function handle(row, promoteToFewshot) {
  const tip = promoteToFewshot
    ? '将标记为已处理, 并把用户纠正的 SQL 沉淀为 few-shot 示例?'
    : '将标记为已处理(不沉淀示例)?'
  try {
    await ElMessageBox.confirm(tip, '处理反馈', { type: 'info' })
  } catch { return }
  try {
    await handleFeedback(row.id, 'HANDLED', promoteToFewshot)
    ElMessage.success('已处理')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function ignore(row) {
  try {
    await ElMessageBox.confirm('确认忽略该反馈(不再提示)?', '提示', { type: 'warning' })
  } catch { return }
  try {
    await handleFeedback(row.id, 'IGNORED', false)
    ElMessage.success('已忽略')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; padding-top: 10px; }
.expand-body { padding: 8px 16px; }
.expand-body p { margin: 4px 0; }
/* 操作列按钮组: 排成一行不换行 */
.op-btns { display: flex; align-items: center; white-space: nowrap; }
.op-btns .el-button { margin-left: 0; margin-right: 14px; }
.sql {
  background: #f5f7fa; padding: 8px 12px; border-radius: 4px;
  font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 4px 0;
}
.done-text { color: #909399; font-size: 12px; }
</style>

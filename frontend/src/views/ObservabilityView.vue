<template>
  <div class="llm-log-view">
    <!-- 汇总统计卡片 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">总调用次数</div>
          <div class="stat-value">{{ stats.totalCalls }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">成功次数</div>
          <div class="stat-value success">{{ stats.successCalls }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">Token 总消耗</div>
          <div class="stat-value">{{ stats.totalTokens }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">平均耗时(成功)</div>
          <div class="stat-value">{{ stats.avgDurationMs }}<span class="stat-unit">ms</span></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="追踪ID">
          <el-input
            v-model="query.bizId"
            placeholder="bizId(一次提问串起多轮调用)"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.success" placeholder="全部" clearable style="width: 110px" @change="handleSearch">
            <el-option label="成功" :value="true" />
            <el-option label="失败" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="auth.isAdmin" label="用户">
          <el-select
            v-model="query.userId"
            placeholder="全部用户"
            clearable
            filterable
            style="width: 190px"
            @change="handleSearch"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.nickname || u.username}(${u.id})`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 日志明细表 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="bizId" label="追踪ID" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.bizId" class="biz-id" @click="copyBizId(row.bizId)">{{ row.bizId }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="userId" label="用户" width="70">
          <template #default="{ row }">
            <span v-if="row.userId != null">{{ row.userId }}</span>
            <span v-else class="text-muted">系统</span>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="150" show-overflow-tooltip />
        <el-table-column label="Token(入/出/总)" width="150">
          <template #default="{ row }">
            {{ row.inputTokens ?? 0 }} / {{ row.outputTokens ?? 0 }} / {{ row.totalTokens ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="finishReason" label="结束原因" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.finishReason" :type="finishTagType(row.finishReason)" size="small">
              {{ row.finishReason }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">
            <span v-if="row.durationMs != null">{{ row.durationMs }} ms</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="small">
              {{ row.success === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage">{{ row.errorMessage }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="落库时间" width="170" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadPage"
          @current-change="loadPage"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageLogs, getStats } from '@/api/observability'
import { pageUsers } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const stats = reactive({ totalCalls: 0, successCalls: 0, totalTokens: 0, avgDurationMs: 0 })
const records = ref([])
const total = ref(0)
const loading = ref(false)
/** 用户下拉选项(admin 用, 按 id 升序取前 100) */
const userOptions = ref([])

const query = reactive({
  current: 1,
  size: 10,
  bizId: '',
  success: null,
  userId: undefined
})

/** 加载分页数据 + 统计 */
async function loadPage() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.bizId) params.bizId = query.bizId
    if (query.success !== null && query.success !== undefined && query.success !== '') {
      params.success = query.success
    }
    if (auth.isAdmin && query.userId) params.userId = query.userId

    const page = await pageLogs(params)
    records.value = page.records || []
    total.value = Number(page.total || 0)
  } finally {
    loading.value = false
  }
  loadStats()
}

async function loadStats() {
  const s = await getStats()
  Object.assign(stats, {
    totalCalls: Number(s?.totalCalls || 0),
    successCalls: Number(s?.successCalls || 0),
    totalTokens: Number(s?.totalTokens || 0),
    avgDurationMs: Number(s?.avgDurationMs || 0)
  })
}

/** admin 专用: 拉取用户列表填充筛选下拉(失败不阻断页面) */
async function loadUserOptions() {
  try {
    const page = await pageUsers({ pageNum: 1, pageSize: 100 })
    userOptions.value = (page.records || page.list || []).sort((a, b) => a.id - b.id)
  } catch {
    // 无权限或加载失败时保持空选项
  }
}

function handleSearch() {
  query.current = 1
  loadPage()
}

function handleReset() {
  query.bizId = ''
  query.success = null
  query.userId = undefined
  handleSearch()
}

function finishTagType(reason) {
  if (reason === 'STOP') return 'success'
  if (reason === 'TOOL_EXECUTION') return 'warning'
  if (reason === 'LENGTH') return 'info'
  return 'danger'
}

async function copyBizId(bizId) {
  try {
    await navigator.clipboard.writeText(bizId)
    ElMessage.success('追踪ID已复制')
  } catch {
    ElMessage.warning('复制失败, 请手动选择复制')
  }
}

onMounted(() => {
  loadPage()
  if (auth.isAdmin) loadUserOptions()
})
</script>

<style scoped>
.llm-log-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stat-card {
  text-align: center;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 700;
  color: #2d4a8f;
}

.stat-value.success {
  color: #67c23a;
}

.stat-unit {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 400;
  color: #909399;
}

.biz-id {
  cursor: pointer;
  font-family: monospace;
  color: #2d4a8f;
}

.biz-id:hover {
  text-decoration: underline;
}

.text-muted {
  color: #c0c4cc;
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>

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
      <el-table v-loading="loading" :data="records" stripe @row-click="openDetail">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="bizId" label="追踪ID" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.bizId" class="biz-id" @click.stop="copyBizId(row.bizId)">{{ row.bizId }}</span>
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
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
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

    <!-- 调用链路详情抽屉(方案B: bizId 打通 llm_call_log + chat_message) -->
    <el-drawer
      v-model="detail.visible"
      :title="detail.currentId ? `调用详情 #${detail.currentId}` : '调用详情'"
      size="640px"
    >
      <div v-loading="detail.loading" class="trace-body">
        <template v-if="detail.trace">
          <!-- 基本信息 -->
          <el-descriptions :column="2" border size="small" class="trace-section">
            <el-descriptions-item label="追踪ID" :span="2">
              <span class="biz-id" @click="copyBizId(detail.trace.bizId)">{{ detail.trace.bizId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="会话">{{ currentMemoryId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模型">{{ currentRow?.model || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Token(入/出/总)">
              {{ currentRow?.inputTokens ?? 0 }} / {{ currentRow?.outputTokens ?? 0 }} / {{ currentRow?.totalTokens ?? 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="耗时">{{ currentRow?.durationMs ?? '-' }} ms</el-descriptions-item>
            <el-descriptions-item label="结束原因">{{ currentRow?.finishReason || '-' }}</el-descriptions-item>
            <el-descriptions-item label="结果">
              <el-tag :type="currentRow?.success === 1 ? 'success' : 'danger'" size="small">
                {{ currentRow?.success === 1 ? '成功' : '失败' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="currentRow?.errorMessage" label="失败原因" :span="2">
              {{ currentRow.errorMessage }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- 调用链(同一次提问的全部模型调用, 工具循环时多轮) -->
          <div class="trace-section">
            <div class="trace-title">调用链({{ detail.trace.chain.length }} 轮)</div>
            <el-timeline class="trace-timeline">
              <el-timeline-item
                v-for="(item, idx) in detail.trace.chain"
                :key="item.id"
                :type="item.id === detail.currentId ? 'primary' : (item.success === 1 ? 'success' : 'danger')"
                :hollow="item.id !== detail.currentId"
                :timestamp="item.createTime"
                placement="top"
              >
                <div class="chain-item" :class="{ active: item.id === detail.currentId }">
                  <span class="chain-round">第 {{ idx + 1 }} 轮</span>
                  <span class="chain-model">{{ item.model || '-' }}</span>
                  <span class="chain-token">
                    token {{ item.inputTokens ?? 0 }}/{{ item.outputTokens ?? 0 }}
                  </span>
                  <span class="chain-duration">{{ item.durationMs ?? '-' }} ms</span>
                </div>
              </el-timeline-item>
            </el-timeline>
          </div>

          <!-- 消息还原(该次提问产生的全部消息, 取自 chat_message.biz_id) -->
          <div class="trace-section">
            <div class="trace-title">消息还原({{ detail.trace.messages.length }} 条)</div>
            <div
              v-for="m in detail.trace.messages"
              :key="m.id"
              class="trace-msg"
              :class="m.role"
            >
              <div class="trace-msg-head">
                <el-tag size="small" :type="roleTagType(m.role)">{{ roleLabel(m.role) }}</el-tag>
                <span class="trace-msg-time">{{ m.createTime }}</span>
              </div>
              <div class="trace-msg-content">{{ m.content || '(工具调用/无文本内容)' }}</div>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageLogs, getStats, getTrace } from '@/api/observability'
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

// ---- 调用链路详情抽屉 ----
const detail = reactive({
  visible: false,
  loading: false,
  currentId: null,
  trace: null
})

/** 当前点击的那条调用记录(抽屉头部基本信息取自此行) */
const currentRow = computed(
  () => detail.trace?.chain?.find((c) => c.id === detail.currentId) || null
)
/** 所属会话 memoryId(从调用链任一条取) */
const currentMemoryId = computed(() => detail.trace?.chain?.[0]?.memoryId || '')

/** 打开详情: 按行上的 bizId 拉取调用链与关联消息 */
async function openDetail(row) {
  if (!row.bizId) {
    ElMessage.warning('该记录没有追踪ID(可能为系统调用或旧数据), 无法关联消息')
    return
  }
  detail.currentId = row.id
  detail.trace = null
  detail.loading = true
  detail.visible = true
  try {
    detail.trace = await getTrace(row.bizId)
  } catch {
    detail.visible = false
  } finally {
    detail.loading = false
  }
}

function roleLabel(role) {
  return { user: '用户', assistant: 'AI', tool: '工具', system: '系统' }[role] || role
}

function roleTagType(role) {
  if (role === 'user') return 'primary'
  if (role === 'assistant') return 'success'
  if (role === 'tool') return 'warning'
  return 'info'
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

/* ---- 详情抽屉 ---- */
.trace-body {
  min-height: 200px;
}

.trace-section {
  margin-bottom: 20px;
}

.trace-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.trace-timeline {
  padding-left: 4px;
}

.chain-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.chain-item.active {
  color: #2d4a8f;
  font-weight: 600;
}

.chain-model {
  color: #606266;
}

.chain-token,
.chain-duration {
  color: #909399;
}

.trace-msg {
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f5f7fa;
}

.trace-msg.user {
  background: #eef2fb;
}

.trace-msg.tool {
  background: #fdf6ec;
}

.trace-msg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.trace-msg-time {
  font-size: 12px;
  color: #c0c4cc;
}

.trace-msg-content {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
</style>

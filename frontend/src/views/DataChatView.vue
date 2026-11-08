<template>
  <div class="dq-page">
    <!-- ==================== 顶部 Banner ==================== -->
    <section class="dq-banner">
      <div class="banner-text">
        <h2 class="banner-title">
          <el-icon><DataAnalysis /></el-icon>
          智能问数 · 用自然语言查数据
        </h2>
        <p class="banner-sub">
          输入业务问题, 系统自动召回数据表、生成 SQL、做安全校验, 再用只读账号执行并返回结果。
          生成语句强制只读, 敏感字段按规则自动脱敏。
        </p>
      </div>
    </section>

    <div class="dq-body">
      <!-- ==================== 左: 问数对话 ==================== -->
      <section class="dq-chat-card">
        <div ref="msgListRef" class="dq-messages">
          <!-- 顶部吸顶流程条: 提问前展示静态流程定义, 提问后实时点亮步骤进度 -->
          <div v-if="displaySteps.length" class="flow flow-sticky">
            <template v-for="(s, i) in displaySteps" :key="s.id">
              <div class="flow-node" :class="'is-' + s.status.toLowerCase()">
                <el-icon v-if="s.status === 'RUNNING'" class="loading-icon"><Loading /></el-icon>
                <el-icon v-else-if="s.status === 'SUCCESS'"><CircleCheck /></el-icon>
                <el-icon v-else-if="s.status === 'FAILED'"><CircleClose /></el-icon>
                <span v-else class="flow-dot"></span>
                <span class="flow-title">{{ s.title }}</span>
                <span v-if="s.ms > 0" class="flow-ms">{{ s.ms }}ms</span>
              </div>
              <el-icon v-if="i < displaySteps.length - 1" class="flow-arrow" :class="{ 'is-done': s.status === 'SUCCESS' }">
                <ArrowRight />
              </el-icon>
            </template>
          </div>

          <!-- 空态 -->
          <div v-if="!messages.length" class="dq-empty">
            <el-icon class="empty-icon"><DataAnalysis /></el-icon>
            <div class="empty-title">开始一次问数</div>
            <div class="empty-sub">例如: 近三个月各城市的订单金额排名</div>
            <div class="empty-samples">
              <el-button
                v-for="s in samples"
                :key="s"
                size="small"
                plain
                @click="ask(s)"
              >
                {{ s }}
              </el-button>
            </div>
          </div>

          <div
            v-for="m in messages"
            :key="m.id"
            class="dq-msg-row"
            :class="m.role"
          >
            <!-- 用户问题 -->
            <template v-if="m.role === 'user'">
              <div class="bubble user-bubble">{{ m.question }}</div>
            </template>

            <!-- 助手回复 -->
            <template v-else>
              <div class="bubble ai-bubble">
                <!-- 流程步骤条: plan 事件下发全部步骤, 随执行逐个点亮。
                     最新一条助手消息的流程已在顶部吸顶条展示, 此处不再重复渲染 -->
                <div v-if="m.steps && m.steps.length && m !== flowMsg" class="flow">
                  <template v-for="(s, i) in m.steps" :key="s.id">
                    <div class="flow-node" :class="'is-' + s.status.toLowerCase()">
                      <el-icon v-if="s.status === 'RUNNING'" class="loading-icon"><Loading /></el-icon>
                      <el-icon v-else-if="s.status === 'SUCCESS'"><CircleCheck /></el-icon>
                      <el-icon v-else-if="s.status === 'FAILED'"><CircleClose /></el-icon>
                      <span v-else class="flow-dot"></span>
                      <span class="flow-title">{{ s.title }}</span>
                      <span v-if="s.ms > 0" class="flow-ms">{{ s.ms }}ms</span>
                    </div>
                    <el-icon v-if="i < m.steps.length - 1" class="flow-arrow" :class="{ 'is-done': s.status === 'SUCCESS' }">
                  <ArrowRight />
                </el-icon>
                  </template>
                </div>

                <!-- 进度 -->
                <div v-if="m.phase === 'running'" class="steps">
                  <div v-for="(p, i) in m.progress" :key="i" class="step">
                    <el-icon class="step-icon loading-icon"><Loading /></el-icon>
                    <span>{{ p }}</span>
                  </div>
                  <div v-if="!m.progress.length" class="step">
                    <el-icon class="step-icon loading-icon"><Loading /></el-icon>
                    <span>正在思考...</span>
                  </div>
                </div>

                <!-- SQL -->
                <div v-if="m.sql" class="sql-block">
                  <div class="sql-head">
                    <span class="sql-title">
                      <el-icon><Document /></el-icon>
                      生成的 SQL
                    </span>
                    <el-button
                      size="small"
                      text
                      @click="copyText(m.sql, 'SQL 已复制')"
                    >
                      <el-icon><CopyDocument /></el-icon> 复制
                    </el-button>
                  </div>
                  <pre class="sql-body">{{ m.sql }}</pre>
                  <div v-if="m.explanation" class="sql-explain">{{ m.explanation }}</div>
                </div>

                <!-- 澄清 -->
                <div v-if="m.result && m.result.clarification" class="clarify">
                  <el-icon><QuestionFilled /></el-icon>
                  <span>{{ m.result.clarification }}</span>
                </div>

                <!-- 结果 -->
                <div v-if="hasResult(m)" class="result-block">
                  <div class="result-head">
                    <span class="result-title">
                      <el-icon><Grid /></el-icon>
                      查询结果
                    </span>
                    <span class="result-meta">
                      {{ m.result.total }} 行 · {{ m.result.executeMs }}ms
                      <el-tag
                        v-if="m.result.truncated"
                        size="small"
                        type="warning"
                        effect="plain"
                      >已截断</el-tag>
                    </span>
                  </div>

                  <div class="result-view-switch">
                    <el-radio-group
                      v-model="m.viewMode"
                      size="small"
                    >
                      <el-radio-button value="auto">自动</el-radio-button>
                      <el-radio-button value="table">表格</el-radio-button>
                      <el-radio-button value="chart">图表</el-radio-button>
                    </el-radio-group>
                  </div>

                  <!-- 图表 -->
                  <DataResultChart
                    v-if="chartVisible(m)"
                    :type="chartTypeOf(m)"
                    :columns="m.result.columns"
                    :rows="m.result.rows"
                  />

                  <!-- 表格 -->
                  <div
                    v-if="tableVisible(m)"
                    class="result-table-wrap"
                  >
                    <el-table
                      :data="tableRows(m)"
                      size="small"
                      border
                      stripe
                      max-height="360"
                    >
                      <el-table-column
                        v-for="c in m.result.columns"
                        :key="c.name"
                        :prop="c.name"
                        :label="c.name"
                        :min-width="120"
                        show-overflow-tooltip
                      >
                        <template #default="{ row }">{{ row[c.name] }}</template>
                      </el-table-column>
                    </el-table>
                  </div>
                </div>

                <!-- 错误 -->
                <div v-if="m.phase === 'error'" class="error-box">
                  <el-icon><WarningFilled /></el-icon>
                  <span class="error-type" v-if="m.errorType">{{ m.errorType }}: </span>
                  <span>{{ m.errorMessage }}</span>
                </div>

                <!-- 反馈 -->
                <div v-if="m.phase === 'done' && m.result && m.result.bizId" class="feedback-row">
                  <span class="fb-label">这个结果有帮助吗?</span>
                  <el-button
                    size="small"
                    :type="m.feedback?.rating === 1 ? 'success' : 'info'"
                    plain
                    @click="vote(m, 1)"
                  >
                    <el-icon><CircleCheck /></el-icon> 赞
                  </el-button>
                  <el-button
                    size="small"
                    :type="m.feedback?.rating === -1 ? 'danger' : 'info'"
                    plain
                    @click="openFeedbackDialog(m)"
                  >
                    <el-icon><CircleClose /></el-icon> 踩
                  </el-button>
                  <span v-if="m.feedback?.rating" class="fb-thanks">已收到反馈, 谢谢!</span>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="dq-input-bar">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            :disabled="sending"
            placeholder="用一句话描述你想查的数据, 回车发送(Ctrl+Enter 换行)"
            @keydown.enter.exact.prevent="send"
          />
          <el-button
            type="primary"
            :loading="sending"
            :disabled="!draft.trim()"
            @click="send"
          >
            <el-icon v-if="!sending"><Promotion /></el-icon>
            提问
          </el-button>
        </div>
      </section>
    </div>

    <!-- ==================== 反馈弹窗 ==================== -->
    <el-dialog v-model="fbVisible" title="反馈这次结果" width="560px">
      <el-form label-width="auto" size="default">
        <el-form-item label="问题">
          <span class="fb-question">{{ fbTarget?.question }}</span>
        </el-form-item>
        <el-form-item label="正确 SQL">
          <el-input
            v-model="fbCorrectSql"
            type="textarea"
            :rows="5"
            placeholder="如果结果不对, 可以贴出你认为正确的 SQL, 我们会把它沉淀为 few-shot 示例"
          />
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input v-model="fbComment" placeholder="例如: 应只统计已完成订单" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fbVisible = false">取消</el-button>
        <el-button type="primary" :loading="fbSubmitting" @click="submitFeedback">
          提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DataAnalysis,
  Document,
  CopyDocument,
  Grid,
  Loading,
  ArrowRight,
  Promotion,
  CircleCheck,
  CircleClose,
  WarningFilled,
  QuestionFilled
} from '@element-plus/icons-vue'
import DataResultChart from '@/components/DataResultChart.vue'
import {
  streamQuery,
  listPipelineSteps,
  submitFeedback as apiSubmitFeedback
} from '@/api/text2sql'

// ==================== 常量 ====================
const SAMPLES = [
  '上个月各城市的订单金额排名',
  '各商品类目的订单量和销售额',
  '订单金额最高的前 10 笔订单',
  '各支付渠道的订单占比'
]

// ==================== 状态 ====================
let seq = 0
const messages = ref([])
const draft = ref('')
const sending = ref(false)
const msgListRef = ref(null)

// 最新一条助手消息: 其流程步骤同时渲染到顶部吸顶条, 滚动查看历史时进度仍可见
const flowMsg = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') return messages.value[i]
  }
  return null
})

// 流程步骤清单(后端编排定义, 提问前预渲染全灰步骤条)
const pipelineSteps = ref([])

// 吸顶条展示内容: 有执行中的流程用执行态, 否则展示静态流程定义
const displaySteps = computed(() => {
  if (flowMsg.value && flowMsg.value.steps.length) return flowMsg.value.steps
  return pipelineSteps.value
})

const fbVisible = ref(false)
const fbSubmitting = ref(false)
const fbTarget = ref(null)
const fbCorrectSql = ref('')
const fbComment = ref('')

// ==================== 工具 ====================
function newId() {
  return ++seq
}

function hasResult(m) {
  return !!(m.result && (m.result.columns?.length || m.result.rows?.length))
}

function chartVisible(m) {
  const mode = m.viewMode || 'auto'
  if (mode === 'table') return false
  if (mode === 'chart') return true
  const t = chartTypeOf(m)
  return t !== 'TABLE' && t !== 'METRIC' && t !== 'EMPTY'
}

function tableVisible(m) {
  const mode = m.viewMode || 'auto'
  if (mode === 'chart') return false
  if (mode === 'table') return true
  return chartTypeOf(m) === 'TABLE' || chartTypeOf(m) === 'METRIC'
}

function chartTypeOf(m) {
  return m.result?.chartType || 'TABLE'
}

function tableRows(m) {
  const cols = (m.result?.columns || []).map((c) => c.name)
  return (m.result?.rows || []).map((r) => {
    const obj = {}
    cols.forEach((name, i) => {
      obj[name] = r[i]
    })
    return obj
  })
}

async function copyText(text, msg) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(msg || '已复制')
  } catch (e) {
    ElMessage.warning('复制失败, 请手动选择复制')
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = msgListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

// ==================== 问数 ====================
function ask(question) {
  draft.value = question
  send()
}

async function send() {
  const question = draft.value.trim()
  if (!question || sending.value) return

  sending.value = true
  // 必须 reactive: SSE 回调闭包持有此对象引用, 裸对象变更不经过 Proxy 不会触发视图更新
  const msg = reactive({
    id: newId(),
    role: 'assistant',
    question,
    phase: 'running',
    progress: [],
    steps: [],
    sql: '',
    explanation: '',
    intent: '',
    result: null,
    errorType: '',
    errorMessage: '',
    feedback: null,
    viewMode: 'auto'
  })
  messages.value.push(
    { id: newId(), role: 'user', question },
    msg
  )
  draft.value = ''
  scrollToBottom()

  try {
    await streamQuery(question, null, (event) => {
      if (event.type === 'plan') {
        // 流程计划: 先渲染完整步骤条, 状态随 step 事件逐个更新
        msg.steps = (event.steps || []).map((s) => ({
          id: s.id,
          title: s.title,
          status: 'PENDING',
          ms: 0,
          message: ''
        }))
      } else if (event.type === 'step') {
        const st = (msg.steps || []).find((x) => x.id === event.id)
        if (st) {
          st.status = event.status
          st.ms = event.ms || 0
          st.message = event.message || ''
        }
      } else if (event.type === 'progress') {
        msg.progress.push(event.message)
      } else if (event.type === 'sql') {
        msg.sql = event.sql
        msg.explanation = event.explanation || ''
      } else if (event.type === 'result') {
        msg.result = event
        msg.intent = event.intent || ''
        msg.phase = 'done'
      } else if (event.type === 'error') {
        msg.errorType = event.errorType || 'ERROR'
        msg.errorMessage = event.message || '未知错误'
        msg.phase = 'error'
      }
      scrollToBottom()
    })
    if (msg.phase === 'running') {
      // 流结束但没收到 result/error, 兜底标记失败
      msg.errorType = 'ERROR'
      msg.errorMessage = '未收到服务端结果'
      msg.phase = 'error'
    }
  } catch (e) {
    msg.errorType = 'ERROR'
    msg.errorMessage = e.message || '请求失败'
    msg.phase = 'error'
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

// ==================== 反馈 ====================
function vote(m, rating) {
  if (!m.result?.bizId) return
  apiSubmitFeedback({ bizId: m.result.bizId, rating, comment: '' })
    .then(() => {
      m.feedback = { rating }
      ElMessage.success('已提交反馈')
    })
    .catch(() => {})
}

function openFeedbackDialog(m) {
  fbTarget.value = m
  fbCorrectSql.value = ''
  fbComment.value = ''
  fbVisible.value = true
}

function submitFeedback() {
  const m = fbTarget.value
  if (!m?.result?.bizId) return
  fbSubmitting.value = true
  apiSubmitFeedback({
    bizId: m.result.bizId,
    rating: -1,
    correctSql: fbCorrectSql.value.trim(),
    comment: fbComment.value.trim()
  })
    .then(() => {
      m.feedback = { rating: -1 }
      fbVisible.value = false
      ElMessage.success('已提交反馈, 运营同学会跟进处理')
    })
    .catch(() => {})
    .finally(() => {
      fbSubmitting.value = false
    })
}

// ==================== 初始化 ====================
onMounted(() => {
  // 拉取流程步骤定义, 提问前即可展示完整步骤条
  listPipelineSteps()
    .then((res) => {
      pipelineSteps.value = (res || []).map((s) => ({
        id: s.id,
        title: s.title,
        status: 'PENDING',
        ms: 0,
        message: ''
      }))
    })
    .catch(() => {})
})
</script>

<style scoped>
.dq-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

/* ============= Banner ============= */
.dq-banner {
  padding: 20px 24px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2d4a8f 0%, #3e6cc7 100%);
  color: #fff;
  display: flex;
  gap: 24px;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  position: relative;
}

.dq-banner::after {
  content: '';
  position: absolute;
  right: -40px;
  top: -40px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  pointer-events: none;
}

.banner-title {
  margin: 0 0 8px;
  font-size: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.banner-title .el-icon {
  font-size: 20px;
}

.banner-sub {
  margin: 0;
  font-size: 13px;
  color: #dbe6f8;
  line-height: 1.7;
}

/* ============= 主体布局 ============= */
.dq-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

/* ============= 对话卡片 ============= */
.dq-chat-card {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.dq-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dq-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #909399;
}

.empty-icon {
  font-size: 42px;
  color: #c6d2ec;
}

.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: #606266;
}

.empty-sub {
  font-size: 13px;
}

.empty-samples {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

/* ============= 消息 ============= */
.dq-msg-row {
  display: flex;
}

.dq-msg-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 88%;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.7;
}

.user-bubble {
  background: #2d4a8f;
  color: #fff;
  word-break: break-word;
}

.ai-bubble {
  background: #f7f8fa;
  border: 1px solid #e8eaee;
  flex: 1;
  min-width: 0;
}

/* 流程步骤条 */
.flow {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 8px;
}
.flow-node {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px;
  border-radius: 14px;
  font-size: 12px;
  color: #a8abb2;
  background: #fff;
  border: 1px solid #e4e7ed;
  transition: color 0.3s, background 0.3s, border-color 0.3s, box-shadow 0.3s;
}
.flow-node.is-running {
  color: #409eff;
  background: #ecf5ff;
  border-color: #a0cfff;
  animation: flow-pulse 1.6s ease-in-out infinite;
}
.flow-node.is-success {
  color: #67c23a;
  background: #f0f9eb;
  border-color: #b3e19d;
}
.flow-node.is-failed {
  color: #f56c6c;
  background: #fef0f0;
  border-color: #fab6b6;
}
.flow-node.is-skipped {
  opacity: 0.5;
}
@keyframes flow-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(64, 158, 255, 0.05);
  }
}
.flow-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #dcdfe6;
}
.flow-title {
  white-space: nowrap;
}
.flow-ms {
  font-size: 11px;
  opacity: 0.75;
}
/* 步骤间箭头: 前一步完成时点亮为绿色, 强化"流向"感 */
.flow-arrow {
  margin: 0 4px;
  font-size: 14px;
  color: #dcdfe6;
  transition: color 0.3s;
}
.flow-arrow.is-done {
  color: #b3e19d;
}

/* 吸顶流程条: 悬浮在消息区顶部, 半透明毛玻璃避免遮挡内容突兀 */
.flow-sticky {
  position: sticky;
  top: 0;
  z-index: 5;
  padding: 8px 12px;
  margin: -8px -8px 10px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(4px);
  border-bottom: 1px solid #ebeef5;
}

/* 进度步骤 */
.steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px dashed #e8eaee;
}

.step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}

.step-icon {
  font-size: 13px;
}

.loading-icon {
  animation: dq-spin 0.9s linear infinite;
}

@keyframes dq-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* SQL 块 */
.sql-block {
  margin-bottom: 12px;
}

.sql-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.sql-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.sql-body {
  margin: 0;
  padding: 10px 12px;
  background: #1f2937;
  color: #e5e7eb;
  border-radius: 6px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}

.sql-explain {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

/* 澄清 */
.clarify {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  color: #b88230;
  font-size: 13px;
}

/* 结果 */
.result-block {
  margin-top: 12px;
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.result-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.result-meta {
  font-size: 12px;
  color: #909399;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.result-view-switch {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.result-table-wrap {
  border-radius: 6px;
  overflow: hidden;
}

/* 错误 */
.error-box {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 6px;
  color: #c45656;
  font-size: 13px;
}

.error-type {
  font-weight: 600;
  flex-shrink: 0;
}

/* 反馈 */
.feedback-row {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e8eaee;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.fb-label {
  font-size: 12px;
  color: #909399;
}

.fb-thanks {
  font-size: 12px;
  color: #67c23a;
}

/* 输入区 */
.dq-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 14px;
  border-top: 1px solid #ebeef5;
  background: #fff;
}

.dq-input-bar .el-button {
  height: 62px;
}

/* ============= 反馈弹窗 ============= */
.fb-question {
  font-size: 13px;
  color: #303133;
}

/* ============= 响应式 ============= */
@media (max-width: 1100px) {
  .dq-body {
    grid-template-columns: 1fr;
  }

  .dq-banner {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>

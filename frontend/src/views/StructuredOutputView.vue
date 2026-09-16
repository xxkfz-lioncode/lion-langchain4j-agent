<template>
  <div class="structured-page">
    <!-- 顶部说明: 三种方式 + 降级链 -->
    <el-card class="banner-card" shadow="never">
      <div class="banner-header">
        <el-icon class="banner-icon"><MagicStick /></el-icon>
        <div>
          <div class="banner-title">LangChain4j 结构化输出 · 三种方式对照</div>
          <div class="banner-sub">同一份文本三种方式并跑, 看「模型侧 schema / 提示词约束」输出的差异</div>
        </div>
      </div>

      <div class="strategy-grid">
        <div v-for="(s, i) in strategies" :key="s.key" class="strategy-cell" :class="['cell-' + i]">
          <div class="strategy-label">
            <el-tag :type="tagType(i)" size="small">{{ s.label }}</el-tag>
          </div>
          <div class="strategy-desc">{{ s.description }}</div>
        </div>
      </div>

      <div class="fallback-tip">
        <el-icon><InfoFilled /></el-icon>
        <span>
          不知道当前模型支持哪种? 用
          <strong>「自动降级」</strong>: JSON Schema &rarr; JSON Mode &rarr; 纯提示词,
          返回第一个成功的结果并附上沿途失败原因。
        </span>
      </div>
    </el-card>

    <!-- 输入区(共用) -->
    <el-card class="input-card" shadow="never">
      <template #header>
        <div class="input-header">
          <span class="input-title">待抽取的自由文本</span>
          <div class="input-actions">
            <el-button :icon="DocumentCopy" size="small" @click="useSample" plain>使用示例</el-button>
            <el-button :icon="Delete" size="small" @click="clearText" plain>清空</el-button>
          </div>
        </div>
      </template>
      <el-input
        v-model="text"
        type="textarea"
        :rows="6"
        resize="none"
        placeholder="例: 刚看完《星际穿越》, 诺兰的叙事依旧震撼, 配乐也很赞。给个 9 分吧, 强烈推荐, 影评人老王。"
      />
      <div class="input-meta">
        <span>目标类型: <el-tag size="small" type="info">MovieReview</el-tag></span>
        <span>字符数: {{ text.length }}</span>
      </div>
    </el-card>

    <!-- 方式切换 + 结果 -->
    <el-card class="result-card" shadow="never">
      <el-tabs v-model="activeTab" class="strategy-tabs">
        <!-- 单方式: JSON Schema / JSON Mode / 纯提示词 -->
        <el-tab-pane
          v-for="tab in singleTabs"
          :key="tab.key"
          :label="tab.label"
          :name="tab.key"
        >
          <div class="pane-actions">
            <el-button
              :type="tab.tone"
              :loading="loading[tab.key]"
              @click="runSingle(tab)"
            >
              <el-icon><MagicStick /></el-icon>
              <span>提取</span>
            </el-button>
          </div>

          <div v-if="results[tab.key]" class="result-block">
            <div class="result-meta">
              <el-tag :type="tab.tone" size="small">{{ tab.label.split(' ')[0] }}</el-tag>
              <span v-if="results[tab.key].ok" class="cost">{{ results[tab.key].costMillis }}ms</span>
            </div>
            <pre v-if="results[tab.key].ok" class="json-box">{{ pretty(results[tab.key].value) }}</pre>
            <el-alert
              v-else
              :title="results[tab.key].error || '请求失败'"
              type="error"
              :closable="false"
              show-icon
            />
          </div>
          <el-empty v-else description="点击「提取」开始" :image-size="60" />
        </el-tab-pane>

        <!-- 自动降级 -->
        <el-tab-pane label="自动降级" name="auto">
          <div class="pane-actions">
            <el-button
              type="success"
              :loading="loading.auto"
              @click="runAuto"
            >
              <el-icon><Refresh /></el-icon>
              <span>自动抽取</span>
            </el-button>
          </div>

          <div v-if="results.auto">
            <div v-if="results.auto.ok" class="result-block">
              <div class="result-meta">
                <el-tag type="success" size="small">最终方式: {{ results.auto.usedStrategy }}</el-tag>
                <span class="cost">{{ results.auto.costMillis }}ms</span>
              </div>
              <div v-if="results.auto.failures && results.auto.failures.length" class="failures">
                <div class="failures-title">
                  <el-icon><WarningFilled /></el-icon> 降级过程(已跳过):
                </div>
                <div v-for="f in results.auto.failures" :key="f" class="failures-item">
                  · {{ f }}
                </div>
              </div>
              <pre class="json-box">{{ pretty(results.auto.value) }}</pre>
            </div>
            <el-alert
              v-else
              :title="results.auto.error || '请求失败'"
              type="error"
              :closable="false"
              show-icon
            />
          </div>
          <el-empty v-else description="点击「自动抽取」开始" :image-size="60" />
        </el-tab-pane>

        <!-- 三种方式对照 -->
        <el-tab-pane label="三种方式对照" name="compare">
          <div class="pane-actions">
            <el-button
              type="warning"
              :loading="loading.compare"
              @click="runCompare"
            >
              <el-icon><Files /></el-icon>
              <span>一键对照</span>
            </el-button>
            <span v-if="results.compare?.ok" class="total-cost">
              总耗时: {{ results.compare.totalCost }}ms
            </span>
          </div>

          <el-table
            v-if="results.compare?.ok"
            :data="compareRows"
            :show-header="true"
            stripe
            border
            class="compare-table"
          >
            <el-table-column prop="label" label="策略" width="160" fixed>
              <template #default="{ row }">
                <el-tag :type="row.tone" size="small">{{ row.label }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="是否成功" width="100">
              <template #default="{ row }">
                <el-tag :type="row.success ? 'success' : 'danger'" size="small">
                  {{ row.success ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="costMillis" label="耗时" width="90">
              <template #default="{ row }">
                <span class="cost-cell">{{ row.costMillis }}ms</span>
              </template>
            </el-table-column>
            <el-table-column label="结果" min-width="320">
              <template #default="{ row }">
                <pre v-if="row.success" class="mini-json">{{ pretty(row.value) }}</pre>
                <div v-else class="mini-error">{{ row.error }}</div>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            v-else-if="results.compare && !results.compare.ok"
            :title="results.compare.error || '请求失败'"
            type="error"
            :closable="false"
            show-icon
          />
          <el-empty v-else description="点击「一键对照」开始" :image-size="60" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  MagicStick,
  DocumentCopy,
  Delete,
  Refresh,
  Files,
  InfoFilled,
  WarningFilled
} from '@element-plus/icons-vue'
import * as api from '@/api/structured'

// ========== 默认示例文案(进入页面即可一键试) ==========
const SAMPLE_TEXT = '刚看完《星际穿越》, 诺兰的叙事依旧震撼, 配乐也很赞。给个 9 分吧, 强烈推荐, 影评人老王。'

// ========== 三种方式元信息(banner 用, 与后端 StructuredOutputStrategy 一一对应) ==========
const strategies = [
  {
    key: 'JSON_SCHEMA',
    label: 'JSON Schema',
    description: '请求里声明 json_schema, 由模型侧保证输出符合 schema(最可靠)'
  },
  {
    key: 'JSON_MODE',
    label: 'JSON Mode',
    description: '提示词描述字段 + response_format=json_object, 只保证是合法 JSON(千问主力)'
  },
  {
    key: 'PROMPT_ONLY',
    label: '纯提示词',
    description: '结构约束全写在提示词里, 模型可能带解释文字或漏字段(最不可靠)'
  }
]

// ========== 单次抽取 Tab 配置(v-for 复用模板) ==========
const singleTabs = [
  { key: 'schema', strategy: 'JSON_SCHEMA', label: 'JSON Schema (最可靠)', tone: 'success' },
  { key: 'mode',   strategy: 'JSON_MODE',   label: 'JSON Mode (千问主力)', tone: 'primary' },
  { key: 'prompt', strategy: 'PROMPT_ONLY', label: '纯提示词 (最不可靠)', tone: 'warning' }
]

// ========== 响应式状态 ==========
const text = ref(SAMPLE_TEXT)
const activeTab = ref('schema')
const loading = reactive({ schema: false, mode: false, prompt: false, auto: false, compare: false })
/**
 * 每个 Tab 的结果:
 *   - null          未运行
 *   - { ok: true,  value, costMillis }    单方式
 *   - { ok: false, error }                单方式 / 自动
 *   - { ok: true,  usedStrategy, value, failures[], costMillis }  自动
 *   - { ok: true,  results, totalCost }   对照
 */
const results = reactive({ schema: null, mode: null, prompt: null, auto: null, compare: null })

// ========== 计算: 对照 Tab 用的表格行 ==========
const compareRows = computed(() => {
  if (!results.compare || !results.compare.ok) return []
  const toneByLabel = {
    'JSON Schema': 'success',
    '提示词 + JSON Mode': 'primary',
    '提示词': 'warning'
  }
  return Object.entries(results.compare.results).map(([label, item]) => ({
    label,
    tone: toneByLabel[label] || 'info',
    success: !!item.success,
    value: item.value,
    error: item.error,
    costMillis: item.costMillis
  }))
})

// ========== 工具 ==========
function pretty(obj) {
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

function tagType(i) {
  return ['success', 'primary', 'warning'][i] || 'info'
}

function useSample() {
  text.value = SAMPLE_TEXT
  ElMessage.success('已填入示例文案')
}
function clearText() {
  text.value = ''
}

function ensureText() {
  if (!text.value || !text.value.trim()) {
    ElMessage.warning('请输入待抽取的文本')
    return false
  }
  return true
}

// ========== 单方式抽取(JSON Schema / JSON Mode / 纯提示词) ==========
async function runSingle(tab) {
  if (!ensureText()) return
  loading[tab.key] = true
  const t0 = performance.now()
  try {
    const value = await api.extract(text.value.trim(), tab.strategy)
    results[tab.key] = {
      ok: true,
      value,
      costMillis: Math.round(performance.now() - t0)
    }
  } catch (e) {
    results[tab.key] = { ok: false, error: e?.message || '请求失败' }
  } finally {
    loading[tab.key] = false
  }
}

// ========== 自动降级 ==========
async function runAuto() {
  if (!ensureText()) return
  loading.auto = true
  const t0 = performance.now()
  try {
    const r = await api.autoExtract(text.value.trim())
    results.auto = {
      ok: true,
      usedStrategy: r.usedStrategy,
      value: r.value,
      failures: r.failures || [],
      costMillis: Math.round(performance.now() - t0)
    }
  } catch (e) {
    results.auto = { ok: false, error: e?.message || '请求失败' }
  } finally {
    loading.auto = false
  }
}

// ========== 三种方式对照 ==========
async function runCompare() {
  if (!ensureText()) return
  loading.compare = true
  const t0 = performance.now()
  try {
    const r = await api.compare(text.value.trim())
    results.compare = {
      ok: true,
      results: r.results,
      totalCost: Math.round(performance.now() - t0)
    }
  } catch (e) {
    results.compare = { ok: false, error: e?.message || '请求失败' }
  } finally {
    loading.compare = false
  }
}
</script>

<style scoped>
.structured-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px;
  max-width: 1280px;
  margin: 0 auto;
}

/* ---------- Banner ---------- */
.banner-card { border-radius: 8px; }
.banner-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.banner-icon { font-size: 22px; color: #2d4a8f; }
.banner-title { font-size: 16px; font-weight: 700; color: #303133; }
.banner-sub { font-size: 12px; color: #909399; margin-top: 2px; }

.strategy-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.strategy-cell {
  padding: 12px 14px;
  border-radius: 6px;
  background: #f7f8fc;
  border: 1px solid #ebeef5;
}
.strategy-label { margin-bottom: 6px; }
.strategy-desc {
  font-size: 12px;
  color: #606266;
  line-height: 1.55;
}
.cell-0 { border-left: 3px solid #67c23a; }
.cell-1 { border-left: 3px solid #409eff; }
.cell-2 { border-left: 3px solid #e6a23c; }

.fallback-tip {
  margin-top: 14px;
  padding: 10px 14px;
  background: #fdf6ec;
  color: #b88230;
  font-size: 13px;
  border-radius: 4px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  line-height: 1.6;
}

/* ---------- 输入区 ---------- */
.input-card { border-radius: 8px; }
.input-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.input-title { font-weight: 600; }
.input-actions { display: flex; gap: 8px; }
.input-meta {
  margin-top: 10px;
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: #909399;
}

/* ---------- 结果区 ---------- */
.result-card { border-radius: 8px; }
.strategy-tabs { padding: 0 4px; }
.pane-actions {
  margin: 8px 0 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.total-cost {
  font-size: 12px;
  color: #909399;
}

.result-block {
  background: #fafbfc;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 14px;
}
.result-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.cost {
  font-size: 12px;
  color: #909399;
}

.json-box {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 14px 16px;
  border-radius: 6px;
  font-family: 'Cascadia Code', 'Fira Code', 'Source Code Pro', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.failures {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fef0f0;
  border-radius: 4px;
  border: 1px solid #fde2e2;
}
.failures-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #c45656;
  font-weight: 600;
  margin-bottom: 4px;
}
.failures-item {
  font-size: 12px;
  color: #c45656;
  margin-left: 22px;
  line-height: 1.7;
}

/* ---------- 对照表 ---------- */
.compare-table { margin-top: 4px; }
.mini-json {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 8px 10px;
  border-radius: 4px;
  font-family: 'Cascadia Code', 'Fira Code', 'Source Code Pro', Consolas, monospace;
  font-size: 11px;
  line-height: 1.5;
  max-height: 220px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
.mini-error {
  font-size: 12px;
  color: #c45656;
  line-height: 1.5;
  word-break: break-all;
}
.cost-cell { font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; }
</style>
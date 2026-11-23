<template>
  <div class="model-manage">
    <div class="page-toolbar">
      <div class="toolbar-title">
        <h3>模型站点</h3>
        <span class="toolbar-sub">站点是唯一一级维度: 同一站点下按「对话 / 向量」分组管理, 共享 URL 与密钥</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="openSiteForm">新建站点</el-button>
    </div>

    <div class="master-detail" v-loading="loading">
      <!-- 左: 站点(供应商/连接)列表 -->
      <aside class="site-panel">
        <div class="panel-title">站点 <span class="count">{{ sites.length }}</span></div>
        <div v-if="sites.length === 0" class="site-empty">暂无站点<br />点击右上「新建站点」</div>
        <div v-for="s in sites" :key="s.baseUrl" class="site-card"
             :class="{ active: activeUrl === s.baseUrl }" @click="activeUrl = s.baseUrl">
          <div class="site-head">
            <span class="site-host">{{ s.host }}</span>
            <el-tag size="small" round effect="plain">{{ s.models.length }} 个模型</el-tag>
          </div>
          <div class="site-url" :title="s.baseUrl">{{ s.baseUrl }}</div>
          <div class="site-meta">
            <span>Key {{ s.apiKeyMasked }}</span>
            <span>{{ s.chatCount }} 对话 · {{ s.embCount }} 向量</span>
          </div>
        </div>
      </aside>

      <!-- 右: 选中站点的模型列表 -->
      <section class="detail-panel">
        <template v-if="activeSite">
          <div class="detail-head">
            <div class="detail-title">
              <h3>{{ activeSite.host }}</h3>
              <span class="detail-url">{{ activeSite.baseUrl }}</span>
            </div>
            <div class="detail-actions">
              <el-button size="small" @click="openSiteEdit(activeSite)">编辑站点</el-button>
              <el-button size="small" type="danger" plain @click="onDeleteSite">删除站点</el-button>
            </div>
          </div>
          <div class="site-summary">
            <span>API Key <b class="m-key">{{ activeSite.apiKeyMasked }}</b></span>
            <span>已连通 <b>{{ activeSite.okCount }} / {{ activeSite.models.length }}</b></span>
            <span>对话默认 <b>{{ chatGroup?.defaultModel || '未设置' }}</b></span>
            <span>向量默认 <b>{{ embGroup?.defaultModel || '未设置' }}</b></span>
          </div>

          <!-- 同一站点下按模型类型分组, 各组独立新建/设默认 -->
          <div v-for="g in activeGroups" :key="g.purpose" class="model-group">
            <div class="group-head">
              <span class="group-title">{{ g.label }}</span>
              <el-tag size="small" round effect="plain">{{ g.models.length }}</el-tag>
              <span class="group-default">默认 {{ g.defaultModel }}</span>
              <el-button class="group-add" size="small" type="primary" plain :icon="Plus"
                         @click="openModelNew(activeSite, g.purpose)">新建{{ g.label }}</el-button>
            </div>
            <el-table :data="g.models" class="model-table">
              <el-table-column label="名称" min-width="140">
                <template #default="{ row }">
                  <span class="m-name" :title="row.remark">{{ row.name }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="modelName" label="模型名" width="150" />
              <el-table-column v-if="g.purpose === 'chat'" label="温度" width="80" align="center">
                <template #default="{ row }">{{ row.temperature ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="连通状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag size="small" round :type="row.status === 'ok' ? 'success' : row.status === 'fail' ? 'danger' : 'info'">
                    {{ row.status === 'ok' ? '已连通' : row.status === 'fail' ? '失败' : '未测试' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="默认" width="110" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.isDefault === 1" type="success" effect="light" size="small" round>已默认</el-tag>
                  <el-button v-else link type="primary" size="small" :disabled="row.enabled !== 1"
                             :title="row.enabled !== 1 ? '请先启用该模型' : '设为该用途的默认模型'"
                             @click="onSetDefault(row)">设为默认</el-button>
                </template>
              </el-table-column>
              <el-table-column label="启停" width="70" align="center">
                <template #default="{ row }">
                  <el-switch :model-value="row.enabled === 1" @change="(v) => onToggle(row, v)" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" :loading="testingId === row.id" @click="onTest(row)">测试</el-button>
                  <el-button link type="primary" size="small" @click="openCopy(row)">复制</el-button>
                  <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <span class="group-empty">该站点暂无{{ g.label }}, 点击「新建{{ g.label }}」添加</span>
              </template>
            </el-table>
          </div>
        </template>
        <el-empty v-else description="请选择或新建一个站点" />
      </section>
    </div>

    <div class="page-hint">
      <el-icon><InfoFilled /></el-icon>
      <span>站点与模型为主从关系: 同一站点下的模型共享 URL / 密钥, 在「编辑站点」中统一修改; 无默认模型时业务回退 application.yml 的系统默认配置</span>
    </div>
    <!-- 主抽屉: 新建站点 / 新建模型 / 编辑模型 / 复制 -->
    <el-drawer v-model="form.visible" size="480px" :destroy-on-close="true" :title="drawerTitle">
      <el-alert v-if="form.mode !== 'site-new'" type="info" :closable="false" show-icon class="form-alert"
                title="站点信息由所属站点继承, 如需修改请用「编辑站点」" />
      <el-alert v-if="form.purpose === 'embedding'" type="warning" :closable="false" show-icon class="form-alert"
                title="向量模型与 Milvus collection 维度强耦合(当前 1024): 测试连通会返回实际维度, 不一致需重建向量库" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px" label-position="left">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="如: 千问-plus-生产" />
        </el-form-item>
        <el-form-item label="模型用途" prop="purpose">
          <el-select v-model="form.purpose" :disabled="form.mode !== 'site-new'">
            <el-option label="对话模型" value="chat" />
            <el-option label="向量模型" value="embedding" />
          </el-select>
        </el-form-item>
        <el-form-item label="协议族" prop="provider">
          <el-select v-model="form.provider" disabled>
            <el-option label="OpenAI 兼容(千问/DeepSeek/Kimi/GLM/Ollama...)" value="openai-compatible" />
          </el-select>
        </el-form-item>
        <el-form-item label="站点地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" :disabled="form.mode !== 'site-new'"
                    placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="form.apiKey" type="password" show-password :disabled="form.mode !== 'site-new'"
                    :placeholder="keyPlaceholder" />
        </el-form-item>
        <el-form-item label="模型名" prop="modelName">
          <el-select v-model="form.modelName" filterable allow-create default-first-option
                     placeholder="选择常用模型或直接输入">
            <el-option v-for="m in MODEL_OPTIONS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.purpose === 'chat'" label="温度" prop="temperature">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="超时(ms)" prop="timeoutMs">
          <el-input-number v-model="form.timeoutMs" :min="5000" :max="600000" :step="5000" :controls="false" style="width: 180px" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>

      <div class="test-bar">
        <el-button :loading="testing" @click="onTestForm">测试连通</el-button>
        <span v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'fail'">
          {{ testResult.ok ? '✓ 连通成功 ' + testResult.elapsedMs + 'ms' : '✗ ' + testResult.error }}
        </span>
      </div>

      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-drawer>

    <!-- 编辑站点: 批量同步该站点下所有模型的 URL / 密钥 -->
    <el-dialog v-model="siteEdit.visible" title="编辑站点" width="480px" :destroy-on-close="true">
      <el-alert type="warning" :closable="false" show-icon class="form-alert"
                :title="'变更将同步到该站点下 ' + (activeSite?.models.length || 0) + ' 个模型, 并重置连通状态'" />
      <el-form label-width="92px" label-position="left">
        <el-form-item label="站点地址">
          <el-input v-model="siteEdit.newBaseUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="siteEdit.apiKey" type="password" show-password
                    placeholder="留空 = 保持原密钥不变" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="siteEdit.saving" @click="onSaveSite">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { InfoFilled, Plus } from '@element-plus/icons-vue'
import {
  listModelConfigs, createModelConfig, updateModelConfig, deleteModelConfig,
  toggleModelConfig, setDefaultModelConfig, testModelConfig, updateModelSite, deleteModelSite
} from '@/api/model'

/** 常用模型名(可手输覆盖, OpenAI 兼容站点模型名自由填写), 按用途区分 */
const COMMON_MODELS = ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen3-max', 'deepseek-v3', 'deepseek-r1', 'kimi-k2', 'glm-4-plus']
const EMBEDDING_MODELS = ['text-embedding-v4', 'text-embedding-v3', 'text-embedding-v1']

const list = ref([])
const loading = ref(false)
const testingId = ref(null)
const activeUrl = ref('')

/** 按 baseUrl 把扁平配置聚合成站点(跨用途: 同一站点下混配对话/向量模型) */
function hostOf(url) {
  try { return new URL(url).host } catch (e) { return url }
}
const sites = computed(() => {
  const map = new Map()
  for (const r of list.value) {
    if (!map.has(r.baseUrl)) {
      map.set(r.baseUrl, { baseUrl: r.baseUrl, provider: r.provider, apiKeyMasked: r.apiKeyMasked, models: [] })
    }
    map.get(r.baseUrl).models.push(r)
  }
  return [...map.values()].map((s) => ({
    ...s,
    host: hostOf(s.baseUrl),
    chatCount: s.models.filter((m) => m.purpose === 'chat').length,
    embCount: s.models.filter((m) => m.purpose === 'embedding').length,
    okCount: s.models.filter((m) => m.status === 'ok').length
  }))
})
const activeSite = computed(() => sites.value.find((s) => s.baseUrl === activeUrl.value) || sites.value[0] || null)

/** 站点详情按用途分组(各组独立新建/设默认) */
const GROUP_DEFS = [
  { purpose: 'chat', label: '对话模型' },
  { purpose: 'embedding', label: '向量模型' }
]
const activeGroups = computed(() => {
  const s = activeSite.value
  if (!s) return []
  return GROUP_DEFS.map((g) => {
    const models = s.models.filter((m) => m.purpose === g.purpose)
    return { ...g, models, defaultModel: models.find((m) => m.isDefault === 1)?.modelName || '未设置' }
  })
})
const chatGroup = computed(() => activeGroups.value.find((g) => g.purpose === 'chat'))
const embGroup = computed(() => activeGroups.value.find((g) => g.purpose === 'embedding'))
watch(sites, (ss) => {
  if (ss.length && !ss.some((s) => s.baseUrl === activeUrl.value)) {
    activeUrl.value = ss[0].baseUrl
  }
}, { immediate: true })

// ---------------- 表单(mode: site-new / model-new / model-edit / model-copy) ----------------
const formRef = ref()
const form = reactive({
  visible: false, mode: 'site-new', id: null, copyKeyFrom: null, purpose: 'chat',
  name: '', provider: 'openai-compatible', baseUrl: '', apiKey: '',
  modelName: '', temperature: 0.7, timeoutMs: 120000, remark: ''
})
/** 常用模型名选项跟随当前模型用途 */
const MODEL_OPTIONS = computed(() => form.purpose === 'embedding' ? EMBEDDING_MODELS : COMMON_MODELS)
const drawerTitle = computed(() => ({
  'site-new': '新建站点(含首个模型)',
  'model-new': '新建' + (form.purpose === 'embedding' ? '向量' : '对话') + '模型',
  'model-edit': '编辑' + (form.purpose === 'embedding' ? '向量' : '对话') + '模型',
  'model-copy': '复制新增(改名称/模型名即可)'
}[form.mode]))
const keyPlaceholder = computed(() => form.mode === 'site-new' ? 'sk-...'
  : form.mode === 'model-edit' ? '留空 = 保持原密钥不变'
    : '留空 = 沿用该站点的密钥(安全不回显)')
const rules = computed(() => ({
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }],
  baseUrl: [
    { required: true, message: '请填写站点地址', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/, message: '必须是 http(s):// 开头的 URL', trigger: 'blur' }
  ],
  apiKey: [{ required: form.mode === 'site-new', message: '新建站点必须填写 API Key', trigger: 'blur' }],
  modelName: [{ required: true, message: '请填写模型名', trigger: 'change' }]
}))

const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)
const siteEdit = reactive({ visible: false, saving: false, baseUrl: '', newBaseUrl: '', apiKey: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    // 一次拉全部用途, 前端按站点/类型聚合(站点是唯一一级维度)
    list.value = (await listModelConfigs()) || []
  } finally {
    loading.value = false
  }
}

function resetForm(mode) {
  testResult.value = null
  form.mode = mode
  form.purpose = 'chat'          // site-new 默认对话; model-new/edit/copy 由调用方覆盖
  form.id = null
  form.copyKeyFrom = null
  form.name = ''
  form.provider = 'openai-compatible'
  form.baseUrl = ''
  form.apiKey = ''
  form.modelName = ''
  form.temperature = 0.7
  form.timeoutMs = 120000
  form.remark = ''
  form.visible = true
}

/** 新建站点 = 新建该站点的第一条模型(站点/密钥可编辑) */
function openSiteForm() {
  resetForm('site-new')
}

/** 站点内新建模型: 站点/密钥只读继承, 后端按 copyKeyFrom 复用密文 */
function openModelNew(site, type) {
  resetForm('model-new')
  form.purpose = type
  form.baseUrl = site.baseUrl
  // 复用同用途模型的密文(同一站点密钥本就共享, 取不到时退回首条)
  const src = site.models.find((m) => m.purpose === type) || site.models[0]
  form.copyKeyFrom = src.id
}

/** 编辑模型: 站点信息只读(避免同一站点被拆成两个) */
function openForm(row) {
  resetForm('model-edit')
  form.id = row.id
  form.purpose = row.purpose
  form.name = row.name
  form.provider = row.provider
  form.baseUrl = row.baseUrl
  form.modelName = row.modelName
  form.temperature = row.temperature ?? 0.7
  form.timeoutMs = row.timeoutMs ?? 120000
  form.remark = row.remark || ''
}

function nextCopyName(base) {
  const names = new Set(list.value.map((x) => x.name))
  let n = base + '-副本'
  let i = 2
  while (names.has(n)) { n = base + '-副本' + i++ }
  return n
}

function openCopy(row) {
  resetForm('model-copy')
  form.id = null
  form.purpose = row.purpose
  form.copyKeyFrom = row.id
  form.name = nextCopyName(row.name)
  form.provider = row.provider
  form.baseUrl = row.baseUrl
  form.modelName = row.modelName
  form.temperature = row.temperature ?? 0.7
  form.timeoutMs = row.timeoutMs ?? 120000
  form.remark = row.remark || ''
}

function openSiteEdit(site) {
  siteEdit.baseUrl = site.baseUrl
  siteEdit.newBaseUrl = site.baseUrl
  siteEdit.apiKey = ''
  siteEdit.visible = true
}
async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    const { visible, mode, ...payload } = form
    if (form.purpose !== 'chat') {
      payload.temperature = null   // 向量/其他用途无温度概念
    }
    const id = form.id || (await createModelConfig(payload))
    if (form.id) {
      await updateModelConfig(form.id, payload)
    }
    // 保存后自动做一次真实连通测试: 后端把结果持久化到 status
    const r = await testModelConfig({
      id, baseUrl: form.baseUrl,
      apiKey: form.apiKey || undefined, modelName: form.modelName
    })
    form.visible = false
    if (form.mode === 'site-new') {
      activeUrl.value = form.baseUrl
    }
    if (r.ok) {
      ElMessage.success('已保存, 连通测试通过 (' + r.elapsedMs + 'ms)')
    } else {
      ElMessage.warning('已保存, 但连通测试失败: ' + r.error)
    }
    load()
  } finally {
    saving.value = false
  }
}

/** 编辑站点: 一次请求同步该站点下所有模型的 URL / 密钥(事务) */
async function onSaveSite() {
  if (!siteEdit.newBaseUrl || !siteEdit.newBaseUrl.trim()) {
    ElMessage.warning('请填写站点地址')
    return
  }
  siteEdit.saving = true
  try {
    await updateModelSite({
      baseUrl: siteEdit.baseUrl,
      newBaseUrl: siteEdit.newBaseUrl.trim(),
      apiKey: siteEdit.apiKey || undefined
    })
    ElMessage.success('站点已更新, 请重新测试该站点下的模型')
    activeUrl.value = siteEdit.newBaseUrl.trim()
    siteEdit.visible = false
    load()
  } finally {
    siteEdit.saving = false
  }
}

/** 删除站点: 批量删除该站点下所有模型; 含默认模型时明确提示业务影响 */
async function onDeleteSite() {
  const s = activeSite.value
  if (!s) return
  const hasDefault = s.models.some((m) => m.isDefault === 1)
  const msg = hasDefault
    ? '将删除「' + s.host + '」站点及全部 ' + s.models.length + ' 个模型(含默认模型, 删除后业务回退系统默认配置), 确定?'
    : '将删除「' + s.host + '」站点及全部 ' + s.models.length + ' 个模型, 确定?'
  await ElMessageBox.confirm(msg, '删除站点', { type: 'warning', confirmButtonText: '删除' })
  const n = await deleteModelSite(s.baseUrl)
  ElMessage.success('站点已删除(' + n + ' 个模型)')
  activeUrl.value = ''
  load()
}

/** 抽屉里的草稿测试(不落库) */
async function onTestForm() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testModelConfig({
      id: form.id || undefined, baseUrl: form.baseUrl,
      apiKey: form.apiKey || undefined, modelName: form.modelName
    })
  } finally {
    testing.value = false
  }
}

/** 行内快速测试 */
async function onTest(row) {
  testingId.value = row.id
  try {
    const r = await testModelConfig({ id: row.id, baseUrl: row.baseUrl, modelName: row.modelName })
    if (r.ok) {
      ElMessage.success('连通成功, 耗时 ' + r.elapsedMs + 'ms')
    } else {
      ElMessage.error('连通失败: ' + r.error)
    }
    load()
  } finally {
    testingId.value = null
  }
}

async function onToggle(row, enabled) {
  await toggleModelConfig(row.id, enabled)
  ElMessage.success(enabled ? '已启用' : '已停用(业务将回退系统默认)')
  load()
}

async function onSetDefault(row) {
  try {
    await setDefaultModelConfig(row.id)
    ElMessage.success('已设为 ' + row.purpose + ' 默认模型')
  } catch (e) {
    // 失败提示由全局拦截器给出
  } finally {
    load()
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm('确定删除模型「' + row.name + '」吗?', '提示', { type: 'warning' })
  await deleteModelConfig(row.id)
  ElMessage.success('已删除')
  load()
}
</script>
<style scoped>
.model-manage {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
}
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.toolbar-title h3 {
  margin: 0 0 2px;
  font-size: 16px;
}
.toolbar-sub {
  font-size: 12px;
  color: #909399;
}

.master-detail {
  display: flex;
  gap: 16px;
  min-height: 420px;
}
.site-panel {
  width: 280px;
  flex-shrink: 0;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  background: #fafafa;
}
.panel-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
  color: #303133;
}
.count {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 10px;
  background: #e9e9eb;
  color: #606266;
  font-size: 12px;
}
.site-empty {
  color: #909399;
  font-size: 13px;
  text-align: center;
  padding: 40px 0;
  line-height: 1.8;
}
.site-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
  padding: 10px 12px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: all .15s;
}
.site-card:hover { border-color: #a0cfff; }
.site-card.active {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, .15);
  background: #f0f8ff;
}
.site-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.site-host {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.site-url {
  font-size: 11px;
  color: #909399;
  margin: 6px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.site-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #a8abb2;
}

.detail-panel { flex: 1; min-width: 0; }
.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.detail-title h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.detail-url {
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.detail-actions { flex-shrink: 0; }
.site-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  padding: 10px 14px;
  margin-bottom: 12px;
  border-radius: 6px;
  background: #f5f7fa;
  font-size: 12px;
  color: #606266;
}
.site-summary b { color: #303133; margin-left: 4px; }

.model-group { margin-bottom: 18px; }
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.group-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.group-default {
  font-size: 12px;
  color: #909399;
  flex: 1;
}
.group-empty {
  font-size: 12px;
  color: #c0c4cc;
}
.m-name { font-weight: 600; }
.m-key { font-family: monospace; color: #909399; font-size: 12px; }

.page-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
.form-alert { margin-bottom: 14px; }
.test-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px solid #ebeef5;
}
.test-result { font-size: 13px; word-break: break-all; }
.test-result.ok { color: #67c23a; }
.test-result.fail { color: #f56c6c; }

@media (max-width: 900px) {
  .master-detail { flex-direction: column; }
  .site-panel { width: 100%; }
}
</style>
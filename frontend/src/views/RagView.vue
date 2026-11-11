<template>
  <div class="rag-page">
    <!-- ==================== 左: 知识库管理 ==================== -->
    <aside class="kb-card">
      <div class="kb-header">
        <div class="kb-title">
          <el-icon class="kb-title-icon"><Collection /></el-icon>
          知识库
        </div>
        <el-tag size="small" type="primary" round effect="light">
          {{ segments }} 个片段
        </el-tag>
      </div>

      <div class="kb-body">
        <!-- 上传按钮 -->
        <el-upload
          action="#"
          :http-request="handleUpload"
          :show-file-list="false"
          :before-upload="beforeUpload"
          accept=".txt,.md,.pdf"
          multiple
          :disabled="uploading"
        >
          <el-button
            type="primary"
            class="upload-btn"
            :loading="uploading"
            :icon="UploadFilled"
          >
            {{ uploading ? '正在上传...' : '上传文档' }}
          </el-button>
          <template #tip>
            <div class="upload-tip">支持 .txt / .md / .pdf, 单文件 ≤ 20MB, 可多选 · 仅保存文件, 点击列表中的「解析」后才会入库</div>
          </template>
        </el-upload>

        <!-- 切分方式: 解析文档时使用的切分参数, 选项由后端 GET /api/rag/splitters 下发 -->
        <div v-if="splitters.length" class="split-box">
          <div class="split-row">
            <span class="split-label">切分方式</span>
            <el-select
              v-model="splitForm.type"
              size="small"
              class="split-select"
              :disabled="uploading"
              @change="onSplitterChange"
            >
              <el-option
                v-for="s in splitters"
                :key="s.code"
                :label="s.label"
                :value="s.code"
              />
            </el-select>
          </div>
          <div class="split-desc">{{ currentSplitter && currentSplitter.description }}</div>

          <div class="split-row split-row-adv">
            <el-switch
              v-model="advanced"
              size="small"
              :disabled="uploading"
              @change="saveSplitPreference"
            />
            <span class="split-adv-label">自定义参数</span>
            <span class="split-adv-tip">
              默认 {{ currentSplitter?.defaultSegmentSize }} / {{ currentSplitter?.defaultOverlap }}
            </span>
          </div>

          <template v-if="advanced">
            <div class="split-row split-row-adv">
              <span class="split-label">块大小</span>
              <el-input-number
                v-model="splitForm.segmentSize"
                size="small"
                class="split-num"
                :min="minSize"
                :max="maxSize"
                :step="50"
                controls-position="right"
                :disabled="uploading"
              />
              <span class="split-label">重叠</span>
              <el-input-number
                v-model="splitForm.overlap"
                size="small"
                class="split-num"
                :min="0"
                :max="maxSize"
                :step="10"
                controls-position="right"
                :disabled="uploading"
              />
            </div>
            <el-input
              v-if="currentSplitter && currentSplitter.patternSupported"
              v-model="splitForm.pattern"
              size="small"
              class="split-pattern"
              :placeholder="currentSplitter.defaultPattern"
              :disabled="uploading"
            />
            <div v-if="currentSplitter && currentSplitter.patternHint" class="split-desc">
              {{ currentSplitter.patternHint }}
            </div>
          </template>
        </div>

        <!-- 文件列表: 名称 / 类型 / 大小 / 状态(待解析/已解析) / 片段数 / 切分方式 / 上传时间 -->
        <div class="file-sec-title">
          <el-checkbox
            v-if="files.length"
            class="select-all"
            :model-value="allSelected"
            :indeterminate="someSelected && !allSelected"
            @change="toggleAll"
          >
            文件列表
          </el-checkbox>
          <template v-else>文件列表</template>
          <span class="file-count">{{ files.length ? `共 ${files.length} 个` : '' }}</span>
        </div>
        <!-- 批量操作栏: 勾选文档后出现(生产知识库产品的标准交互: 勾选 → 批量解析/删除) -->
        <div v-if="someSelected" class="batch-bar">
          <span class="batch-count">已选 {{ selectedIds.length }} 项</span>
          <div class="batch-ops">
            <el-button
              size="small"
              type="primary"
              :loading="batchParsing"
              @click="handleBatchParse"
            >
              解析所选
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="batchParsing"
              @click="handleBatchDelete"
            >
              删除所选
            </el-button>
          </div>
        </div>
        <template v-if="files.length">
          <div
            v-for="f in files"
            :key="f.id"
            class="file-item"
            :class="{ selected: selectedIds.includes(f.id) }"
          >
            <el-checkbox
              class="file-check"
              :model-value="selectedIds.includes(f.id)"
              @change="toggleSelect(f)"
            />
            <el-icon class="file-icon" :class="'type-' + (f.fileType || 'txt')">
              <Document />
            </el-icon>
            <div class="file-info">
              <div class="file-name" :title="`${f.fileName} (${f.id})`">{{ f.fileName }}</div>
              <div class="file-meta">
                <span class="meta-size">{{ f.sizeText }}</span>
                ·
                <span v-if="f.status === 0" class="meta-status pending">待解析</span>
                <template v-else>
                  <span class="meta-seg">{{ f.segmentCount }} 片段</span>
                  ·
                  <span class="meta-split" :title="f.splitterTitle">{{ f.splitterText }}</span>
                  ·
                </template>
                <span class="meta-time">{{ f.timeText }}</span>
              </div>
            </div>
            <div class="file-actions">
              <el-button
                size="small"
                :type="f.status === 0 ? 'primary' : 'default'"
                plain
                :loading="parsingId === f.id"
                :disabled="batchParsing && parsingId !== f.id"
                @click="handleParse(f)"
              >
                {{ f.status === 0 ? '解析' : '重新解析' }}
              </el-button>
              <el-tooltip content="删除文档及其向量" placement="top">
                <el-icon class="file-del" @click="handleDelete(f)"><Close /></el-icon>
              </el-tooltip>
            </div>
          </div>
        </template>
        <el-empty
          v-else
          description="暂无文档, 上传后勾选并点「解析所选」入库"
          :image-size="56"
          class="file-empty"
        />
      </div>

      <!-- 使用说明 -->
      <div class="kb-tips">
        <div class="kb-tips-title">
          <el-icon><InfoFilled /></el-icon>
          使用说明
        </div>
        <ol class="kb-tips-list">
          <li>上传仅保存文件(不产生解析费用), 勾选文档后点「解析所选」(或单条「解析」)才会切分、向量化入库;</li>
          <li>解析时按上方所选切分方式执行, 已解析文档可「重新解析」(换切分策略自动替换旧片段);</li>
          <li>追加式入库, 多份文档<b>共存</b>于知识库, 可单独删除;</li>
          <li>提问前请先解析资料, 回答将仅依据库内内容生成。</li>
        </ol>
      </div>
    </aside>

    <!-- ==================== 右: 知识问答 ==================== -->
    <div class="qa-card">
      <div class="qa-toolbar">
        <div class="qa-title">
          <span class="qa-dot"></span>
          知识问答
          <span class="qa-sub">基于文档内容回答 · 千问模型</span>
        </div>
        <el-button
          size="small"
          :disabled="loading || !segments"
          @click="handleClear"
        >
          <el-icon class="toolbar-icon"><Brush /></el-icon>
          清空对话
        </el-button>
      </div>

      <!-- 消息列表 -->
      <main ref="listRef" class="qa-body">
        <div
          v-for="(item, index) in messages"
          :key="index"
          class="msg-row"
          :class="item.role"
        >
          <div class="msg-avatar">{{ item.role === 'user' ? '我' : 'AI' }}</div>
          <div class="msg-content">
            <div
              v-if="item.role === 'assistant'"
              class="msg-bubble markdown-body"
              v-html="renderMd(item.content) + (item.streaming ? CURSOR_HTML : '')"
            ></div>
            <div v-else class="msg-bubble">{{ item.content }}</div>
          </div>
        </div>

        <div v-if="!messages.length && !loading" class="qa-empty">
          <div class="qa-empty-icon"><el-icon><Reading /></el-icon></div>
          <div class="qa-empty-title">知识库问答</div>
          <div class="qa-empty-desc">
            {{ segments ? '在下方输入你的问题, 我会依据文档内容回答' : '先上传并解析一份知识文档, 再向我提问' }}
          </div>
        </div>
      </main>

      <!-- 输入区 -->
      <footer class="qa-footer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          :disabled="loading"
          placeholder="请输入你的问题, Enter 发送, Shift+Enter 换行"
          @keydown.enter.exact.prevent="handleSend"
        />
        <el-button
          type="primary"
          class="send-btn"
          :loading="loading"
          :disabled="!segments"
          @click="handleSend"
        >
          发送
        </el-button>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  UploadFilled,
  Collection,
  Document,
  Close,
  InfoFilled,
  Brush,
  Reading
} from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { uploadRagFile, parseRagDocument, ragChat, getRagDocuments, deleteRagDocument, getRagSplitters } from '@/api/rag'

const listRef = ref()
const input = ref('')
const loading = ref(false)
const uploading = ref(false)
const messages = ref([])
const segments = ref(0)
const files = ref([])

// ---------------- 切分方式(上传参数) ----------------
/** 后端支持的切分方式(由 SplitterType 枚举下发); 接口不可用时为空, 上传走后端默认切分 */
const splitters = ref([])
/** 上传使用的切分方式; 记住上次选择, 刷新页面不丢(仅前端偏好) */
const SPLIT_STORE_KEY = 'rag-split-preference'
const splitForm = ref({ type: 'recursive', segmentSize: null, overlap: null, pattern: '' })
/** 是否展开自定义参数(块大小 / 重叠 / 正则); 不展开时由后端取该方式默认值 */
const advanced = ref(false)
const currentSplitter = computed(() => splitters.value.find((s) => s.code === splitForm.value.type))
const minSize = computed(() => currentSplitter.value?.minSegmentSize ?? 50)
const maxSize = computed(() => currentSplitter.value?.maxSegmentSize ?? 4000)

// 流式输出时挂在 AI 气泡末尾的打字机光标(v-html 内联注入, 样式走非 scoped 全局)
const CURSOR_HTML = '<span class="stream-cursor"></span>'

/** 将 markdown 渲染为安全 HTML(先 marked 转 HTML, 再 DOMPurify 过滤防 XSS) */
function renderMd(text = '') {
  const raw = String(text || '')
  try {
    return DOMPurify.sanitize(marked.parse(raw, { breaks: true, gfm: true }))
  } catch (e) {
    return raw
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

// ---------------- 切分方式: 选择与参数组装 ----------------

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

/** 记住本次选择(仅前端偏好, 失败不影响上传) */
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

/** 组装上传参数: 未展开自定义参数时只传方式, 其余由后端取该方式默认值 */
function splitOptions() {
  const options = { splitterType: splitForm.value.type }
  if (advanced.value) {
    options.segmentSize = splitForm.value.segmentSize
    options.overlap = splitForm.value.overlap
    if (currentSplitter.value?.patternSupported) options.pattern = splitForm.value.pattern
  }
  return options
}

/** 拉取后端支持的切分方式并恢复上次选择(接口不可用则隐藏选择区, 上传走后端默认切分) */
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

// ---------------- 知识库上传 ----------------

const MAX_SIZE = 20 * 1024 * 1024

/** 上传前校验: 扩展名 + 大小(与后端保持一致, 尽早拦截) */
function beforeUpload(file) {
  const name = file.name || ''
  const lower = name.toLowerCase()
  const supported = /\.(txt|md|markdown|pdf)$/.test(lower)
  if (!supported) {
    ElMessage.error('仅支持 .txt / .md / .pdf 格式的知识文档')
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.error('文件大小不能超过 20MB')
    return false
  }
  return true
}

let uploadCount = 0

/** 自定义上传: 仅保存文件不解析(切分入库由「解析」按钮触发); 失败由拦截器弹出错误; 支持多文件并行 */
async function handleUpload({ file, onSuccess, onError }) {
  uploadCount++
  uploading.value = true
  try {
    const data = await uploadRagFile(file)
    // 以服务端落库结果为准刷新列表(含"待解析"状态)
    await loadDocuments()
    // 生产级引导: 上传成功自动勾选新文档, 批量操作栏立刻出现"解析所选"入口
    if (data?.id && !selectedIds.value.includes(data.id)) {
      selectedIds.value.push(data.id)
    }
    ElMessage.success(`「${data.fileName}」已上传, 点击「解析所选」入库`)
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

// ---------------- 解析(切分 + 向量化入库) ----------------

/** 正在解析的文档 id(行内按钮 loading) */
const parsingId = ref(null)

/** 批量解析进行中(期间禁用删除等操作) */
const batchParsing = ref(false)

/** 勾选的文档 id 列表 */
const selectedIds = ref([])

const someSelected = computed(() => selectedIds.value.length > 0)
const allSelected = computed(
  () => files.value.length > 0 && selectedIds.value.length === files.value.length
)

/** 勾选/取消单个文档 */
function toggleSelect(f) {
  const idx = selectedIds.value.indexOf(f.id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(f.id)
}

/** 全选/全不选 */
function toggleAll(checked) {
  selectedIds.value = checked ? files.value.map((f) => f.id) : []
}

/** 刷新列表后剔除已不存在的勾选项 */
function pruneSelection() {
  const ids = new Set(files.value.map((f) => f.id))
  selectedIds.value = selectedIds.value.filter((id) => ids.has(id))
}

/**
 * 解析/重新解析文档: 按顶部面板当前选择的切分方式执行。
 * 对已解析文档会先清空旧向量再按新配置重建(重新解析有二次确认)。
 */
async function handleParse(f) {
  const isReParse = f.status === 1
  if (isReParse) {
    try {
      await ElMessageBox.confirm(
        `确定重新解析「${f.fileName}」吗? 其现有 ${f.segmentCount || 0} 个片段将被清除, 按当前切分方式重建。`,
        '重新解析',
        { type: 'warning', confirmButtonText: '重新解析', cancelButtonText: '取消' }
      )
    } catch (e) {
      return // 用户取消
    }
  }
  parsingId.value = f.id
  try {
    saveSplitPreference()
    // 未展开自定义参数时只传方式, 其余由后端取该方式默认值
    const data = await parseRagDocument(f.id, splitOptions())
    await loadDocuments()
    pruneSelection()
    ElMessage.success(
      `「${data.fileName}」解析完成, ${splitterLabel(data)} 切分入库 ${data.segments} 个片段`
    )
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.message || '未知错误'))
  } finally {
    parsingId.value = null
  }
}

/**
 * 批量解析: 对勾选的文档逐个按当前切分方式解析(串行执行, 避免并发打满 embedding 限流)。
 * 含已解析文档时先统一确认"重新解析会清掉旧片段"。
 */
async function handleBatchParse() {
  const targets = files.value.filter((f) => selectedIds.value.includes(f.id))
  if (!targets.length) return
  const reParseCount = targets.filter((f) => f.status === 1).length
  if (reParseCount > 0) {
    try {
      await ElMessageBox.confirm(
        `所选 ${targets.length} 个文档中有 ${reParseCount} 个已解析过, 重新解析将清除其现有片段并按当前切分方式重建。继续吗?`,
        '批量解析',
        { type: 'warning', confirmButtonText: '开始解析', cancelButtonText: '取消' }
      )
    } catch (e) {
      return // 用户取消
    }
  }
  batchParsing.value = true
  saveSplitPreference()
  let ok = 0
  let fail = 0
  try {
    for (const f of targets) {
      parsingId.value = f.id
      try {
        const data = await parseRagDocument(f.id, splitOptions())
        ok++
        ElMessage.success(`「${data.fileName}」解析完成, 入库 ${data.segments} 个片段`)
      } catch (e) {
        fail++
        ElMessage.error(`「${f.fileName}」解析失败: ${e.message || '未知错误'}`)
      }
    }
  } finally {
    parsingId.value = null
    batchParsing.value = false
    await loadDocuments()
    pruneSelection()
  }
  if (ok > 0 && fail === 0) {
    ElMessage.success(`批量解析完成: ${ok} 个文档已入库`)
  } else if (fail > 0) {
    ElMessage.warning(`批量解析结束: 成功 ${ok} 个, 失败 ${fail} 个(失败的可单独重试)`)
  }
}

/** 批量删除勾选文档(含其向量), 统一确认后逐个执行 */
async function handleBatchDelete() {
  const targets = files.value.filter((f) => selectedIds.value.includes(f.id))
  if (!targets.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除所选 ${targets.length} 个文档吗? 共 ${targets.reduce((s, f) => s + (f.segmentCount || 0), 0)} 个片段将从知识库一并移除。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  batchParsing.value = true
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
    batchParsing.value = false
    await loadDocuments()
    pruneSelection()
  }
  if (fail === 0) ElMessage.success('所选文档已删除')
}

/** 切分方式编码 → 短名(优先用后端下发的短名, 接口不可用退回编码) */
function splitterShort(code) {
  return splitters.value.find((s) => s.code === code)?.shortLabel || code || '默认'
}

/** 文档/上传结果 → "递归 300/50"; 无切分信息返回空串 */
function splitterLabel(item) {
  if (!item || !item.splitterType) return ''
  return `${splitterShort(item.splitterType)} ${item.segmentSize}/${item.overlapSize}`
}

/** 拉取文档列表并补充展示字段(大小/时间/切分方式), 同时同步总片段数 */
async function loadDocuments() {
  const list = (await getRagDocuments()) || []
  files.value = list.map((d) => ({
    ...d,
    sizeText: formatSize(d.fileSize),
    timeText: formatTimeText(d.createTime),
    splitterText: splitterLabel(d),
    splitterTitle: `切分方式: ${splitterLabel(d)}${d.splitterPattern ? ` · 正则 ${d.splitterPattern}` : ''}`
  }))
  segments.value = list.reduce((sum, d) => sum + (d.segmentCount || 0), 0)
}

/** 删除文档(含其向量), 带确认弹窗 */
async function handleDelete(f) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${f.fileName}」吗? 该文件的 ${f.segmentCount || 0} 个片段将从知识库一并移除。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  try {
    await deleteRagDocument(f.id)
    ElMessage.success(`「${f.fileName}」已删除`)
    await loadDocuments()
    pruneSelection()
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || '未知错误'))
  }
}

// ---------------- 知识问答 ----------------

async function handleSend() {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (!segments.value) {
    const hasUnparsed = files.value.some((f) => f.status === 0)
    ElMessage.warning(
      hasUnparsed
        ? '知识库中还有待解析的文档, 请先点击「解析」入库'
        : '知识库还是空的, 请先上传并解析一份文档'
    )
    return
  }

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  const aiMsg = { role: 'assistant', content: '', streaming: true }
  messages.value.push(aiMsg)
  loading.value = true
  scrollToBottom()

  try {
    const reply = (await ragChat(text)) || ''
    aiMsg.streaming = false
    if (!reply) {
      aiMsg.content = '(空回复)'
      loading.value = false
      scrollToBottom()
      return
    }
    // 整段返回后用打字机逐字吐出, 保证阅读节奏
    let shown = 0
    const step = Math.max(1, Math.round(reply.length / 40))
    function pump() {
      shown = Math.min(reply.length, shown + step)
      aiMsg.content = reply.slice(0, shown)
      scrollToBottom()
      if (shown < reply.length) {
        setTimeout(pump, 16)
      } else {
        aiMsg.content = reply
        loading.value = false
        scrollToBottom()
      }
    }
    pump()
  } catch (e) {
    aiMsg.content = '抱歉, 请求失败: ' + (e.message || '未知错误')
    aiMsg.streaming = false
    loading.value = false
    scrollToBottom()
  }
}

function handleClear() {
  messages.value = []
  scrollToBottom()
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

onMounted(async () => {
  await loadSplitters()
  try {
    await loadDocuments()
  } catch (e) {
    // 拉取失败不阻断页面
  }
})
</script>

<style scoped>
.rag-page {
  height: 100%;
  display: flex;
  gap: 16px;
}

/* ---------- 左: 知识库卡片 ---------- */
.kb-card {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.kb-header {
  height: 52px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.kb-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 6px;
}

.kb-title-icon {
  color: #2d4a8f;
}

.kb-body {
  padding: 16px;
  overflow-y: auto;
}

.upload-btn {
  width: 100%;
}

.file-count {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}

.upload-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

/* 切分方式设置 */
.split-box {
  margin-top: 12px;
  padding: 10px 12px;
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
  margin-top: 8px;
}

.split-label {
  flex-shrink: 0;
  font-size: 12px;
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
  font-size: 12px;
  color: #606266;
}

.split-adv-tip {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}

.split-num {
  width: 96px;
}

.split-pattern {
  margin-top: 8px;
}

.meta-split {
  color: #2d6cdf;
}

.file-sec-title {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8f9fb;
  margin-bottom: 8px;
  transition: background 0.15s, box-shadow 0.15s;
}

.file-item.selected {
  background: #eef4ff;
  box-shadow: inset 0 0 0 1px #b8d0fb;
}

.file-check {
  flex-shrink: 0;
  height: auto;
}

/* 批量操作栏: 勾选文档后出现在列表上方 */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 10px;
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

.select-all {
  height: auto;
}

.select-all :deep(.el-checkbox__label) {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.file-icon {
  color: #2d6cdf;
  font-size: 16px;
  flex-shrink: 0;
}

.file-info {
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.file-del {
  flex-shrink: 0;
  font-size: 14px;
  color: #c0c4cc;
  cursor: pointer;
}

.file-del:hover {
  color: #f56c6c;
}

/* 行内操作区: 解析/重新解析按钮 + 删除图标 */
.file-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* 待解析状态标记 */
.meta-status {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 8px;
}

.meta-status.pending {
  color: #b8860b;
  background: #fdf3dd;
}

/* 文件类型图标配色: pdf 红 / txt 绿 / md 蓝 */
.file-icon.type-pdf {
  color: #f56c6c;
}

.file-icon.type-txt {
  color: #67c23a;
}

.file-icon.type-md {
  color: #2d6cdf;
}

.file-empty {
  padding: 6px 0;
}

/* 使用说明 */
.kb-tips {
  margin: auto 12px 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f7fd;
  border: 1px solid #e3ecfb;
}

.kb-tips-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: #2d4a8f;
}

.kb-tips-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: #606266;
  line-height: 1.9;
}

.kb-tips-list b {
  color: #f56c6c;
}

/* ---------- 右: 问答卡片 ---------- */
.qa-card {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.qa-toolbar {
  height: 52px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8eaee;
  flex-shrink: 0;
}

.qa-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.qa-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #67c23a;
}

.qa-sub {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}

.toolbar-icon {
  margin-right: 4px;
}

.qa-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 20px;
  background: #fafbfc;
}

.qa-empty {
  padding: 60px 0;
  text-align: center;
}

.qa-empty-icon {
  font-size: 44px;
  color: #c0c4cc;
}

.qa-empty-title {
  margin-top: 10px;
  font-size: 15px;
  font-weight: 600;
  color: #606266;
}

.qa-empty-desc {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

.msg-row {
  display: flex;
  margin-bottom: 18px;
  gap: 10px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  flex-shrink: 0;
}

.msg-row.assistant .msg-avatar {
  background: #2d4a8f;
}

.msg-row.user .msg-avatar {
  background: #67c23a;
}

.msg-content {
  max-width: 70%;
  display: flex;
  align-items: flex-start;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-row.assistant .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 2px;
}

.msg-row.user .msg-bubble {
  background: #2d6cdf;
  color: #fff;
  border-top-right-radius: 2px;
}

.qa-footer {
  padding: 12px 20px 16px;
  border-top: 1px solid #e8eaee;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-shrink: 0;
}

.send-btn {
  height: 80px;
}
</style>

<style>
/* v-html 注入的 markdown 元素不在 scoped 作用域内, 排版样式必须放这里(全局) */
.markdown-body {
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
  color: #303133;
}
.markdown-body > *:first-child {
  margin-top: 0;
}
.markdown-body > *:last-child {
  margin-bottom: 0;
}
.markdown-body p {
  margin: 0 0 10px;
}
.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4,
.markdown-body h5,
.markdown-body h6 {
  margin: 16px 0 10px;
  font-weight: 600;
  line-height: 1.4;
  color: #1f2d3d;
}
.markdown-body ul,
.markdown-body ol {
  margin: 6px 0 10px;
  padding-left: 22px;
}
.markdown-body li {
  margin: 3px 0;
}
.markdown-body code {
  padding: 2px 5px;
  margin: 0 2px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  background: #f5f7fa;
  color: #c7254e;
}
.markdown-body pre {
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: 6px;
  overflow-x: auto;
  background: #282c34;
  color: #abb2bf;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.5;
}
.markdown-body pre code {
  padding: 0;
  margin: 0;
  background: transparent;
  color: inherit;
  font-size: 13px;
}
.markdown-body blockquote {
  margin: 10px 0;
  padding: 4px 14px;
  border-left: 4px solid #409eff;
  background: #f0f7ff;
  color: #606266;
}
.markdown-body blockquote p {
  margin: 6px 0;
}
.markdown-body table {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 13px;
}
.markdown-body table th,
.markdown-body table td {
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  text-align: left;
}
.markdown-body table th {
  background: #f5f7fa;
  font-weight: 600;
}
.markdown-body a {
  color: #2d6cdf;
  text-decoration: none;
}
.markdown-body a:hover {
  text-decoration: underline;
}

/* 流式输出末尾的打字机光标(v-html 注入, 故非 scoped) */
.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: #2d6cdf;
  animation: cursor-blink 0.8s steps(2) infinite;
}
@keyframes cursor-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>

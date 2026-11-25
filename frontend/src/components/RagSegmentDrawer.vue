<template>
  <el-drawer
    :model-value="modelValue"
    size="58%"
    :destroy-on-close="true"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <template #header>
      <div class="drawer-header">
        <span class="drawer-title" :title="document?.fileName">{{ document?.fileName }}</span>
        <span class="drawer-id">#{{ document?.id }}</span>
        <el-tag size="small" type="success" effect="light" round>已解析</el-tag>
      </div>
    </template>

    <!-- 统计条 + 搜索工具栏(固定不随列表滚动) -->
    <div class="drawer-toolbar">
      <span class="seg-stat">
        {{ keyword ? '命中 ' + total + ' 条' : '共 ' + total + ' 个片段' }}
      </span>
      <el-input
        v-model="keyword"
        class="seg-search"
        placeholder="搜索片段内容, 回车确认"
        clearable
        :prefix-icon="Search"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-tooltip content="跳转向量库查询页, 按该文档过滤" placement="top">
        <el-button :icon="Coin" @click="goVector">向量库</el-button>
      </el-tooltip>
    </div>

    <!-- 片段卡片列表 -->
    <div v-loading="loading" class="seg-list">
      <el-empty v-if="!loading && records.length === 0" description="暂无片段" />
      <div v-for="s in records" :key="s.id" class="seg-card" @click="toggle(s.seq)">
        <div class="seg-meta">
          <span class="seg-seq" title="片段顺序号: 本次解析第几个产出(从 1 起); 重新解析会按新切分结果重排">片段 {{ s.seq + 1 }}</span>
          <span v-if="s.page != null" class="seg-page">第 {{ s.page }} 页</span>
          <el-tag v-if="s.elementType && s.elementType !== 'text' && s.elementType !== 'paragraph'"
                  size="small" :type="s.elementType === 'table' ? 'warning' : 'info'"
                  effect="plain" round>{{ typeLabel(s.elementType) }}</el-tag>
          <span v-if="s.sectionPath" class="seg-section" :title="s.sectionPath">{{ s.sectionPath }}</span>
          <span v-if="s.rowStart != null" class="seg-rows"
                title="表格数据行(不含每块重复的表头): 本片段覆盖范围 / 整张表总行数">
            第 {{ s.rowStart }}~{{ s.rowEnd }} 行 / 共 {{ s.rowTotal }} 行
          </span>
          <span class="seg-chars">{{ s.charCount }} 字</span>
          <span class="seg-actions" @click.stop>
            <el-button link type="primary" size="small" @click="toggle(s.seq)">
              {{ expanded.includes(s.seq) ? '收起' : '展开' }}
            </el-button>
            <el-tooltip content="复制片段原文" placement="top">
              <el-button link size="small" :icon="CopyDocument" @click="copyText(s.content)" />
            </el-tooltip>
          </span>
        </div>
        <!-- 3 行截断 + 关键字高亮; 展开状态翻页后重置 -->
        <div
          class="seg-content"
          :class="{ expanded: expanded.includes(s.seq) }"
          v-html="display(s.content)"
        />
      </div>
    </div>

    <!-- 底部分页 -->
    <div class="drawer-footer">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next, sizes"
        background
        @current-change="onPageChange"
        @size-change="onPageChange"
      />
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, Coin, Search } from '@element-plus/icons-vue'
import { getRagSegments } from '@/api/rag'

const props = defineProps({
  /** 抽屉开关(v-model, 规范绑定 modelValue) */
  modelValue: { type: Boolean, default: false },
  /** 目标文档(rag_document VO, 需 status=1) */
  document: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue'])

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref([])
/** 展开全文的片段 seq 集合(翻页/搜索后重置) */
const expanded = ref([])

watch(() => props.modelValue, (v) => {
  if (v) {
    keyword.value = ''
    page.value = 1
    size.value = 10
    expanded.value = []
    load()
  }
})

async function load() {
  if (!props.document?.id) return
  loading.value = true
  try {
    const data = await getRagSegments(props.document.id, {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined
    })
    total.value = data.total || 0
    records.value = data.records || []
  } catch (e) {
    ElMessage.error('片段明细加载失败: ' + (e.message || '未知错误'))
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 翻页/改每页条数: 展开状态重置 + 滚回顶部 */
function onPageChange() {
  expanded.value = []
  load()
  window.document.querySelector('.seg-list')?.scrollTo({ top: 0 })
}

/** 搜索: 回到第一页重新查 */
function onSearch() {
  page.value = 1
  expanded.value = []
  load()
}

/** 元素类型 → 展示文案 */
function typeLabel(t) {
  return { table: '表格', title: '标题', list: '列表', paragraph: '段落', text: '文本' }[t] || t
}

function toggle(seq) {
  expanded.value = expanded.value.includes(seq)
    ? expanded.value.filter((x) => x !== seq)
    : [...expanded.value, seq]
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('片段原文已复制')
  } catch {
    ElMessage.error('复制失败(浏览器未授权剪贴板)')
  }
}

/** 跳转向量库查询页: 按文件名过滤(向量页支持 fileName 筛选) */
function goVector() {
  emit('update:modelValue', false)
  router.push({ name: 'vector', query: { fileName: props.document?.fileName } })
}

/**
 * 片段内容 → 高亮 HTML: 先 HTML 转义防注入, 再替换关键字为 <mark>
 * (片段来自用户上传文档, v-html 前必须转义)
 */
function display(content) {
  let html = (content || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  const kw = keyword.value.trim()
  if (kw) {
    const esc = kw.replace(/[.*+?^`(){}[\]\\|]/g, '\\$&')
    html = html.replace(new RegExp(esc, 'gi'), (m) => '<mark>' + m + '</mark>')
  }
  return html
}
</script>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-id {
  font-size: 12px;
  color: #909399;
  font-family: monospace;
  flex-shrink: 0;
}

/* 工具栏: 固定在抽屉顶部 */
.drawer-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.seg-stat {
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
}
.seg-search {
  flex: 1;
}

/* 列表区: 占满剩余空间滚动 */
.seg-list {
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
}
.seg-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.seg-card:hover {
  border-color: #2d6cdf;
}
.seg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.seg-seq {
  font-size: 12px;
  font-weight: 600;
  color: #2d6cdf;
  font-family: monospace;
}
.seg-page {
  font-size: 12px;
  color: #909399;
  background: #f4f4f5;
  border-radius: 4px;
  padding: 1px 6px;
}
.seg-chars {
  font-size: 12px;
  color: #909399;
}
.seg-rows {
  font-size: 12px;
  color: #b8860b;
  background: #fdf6ec;
  border-radius: 4px;
  padding: 1px 6px;
  white-space: nowrap;
}
.seg-section {
  font-size: 12px;
  color: #2d6cdf;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}
.seg-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
}

/* 内容: 默认 3 行截断, .expanded 展开全文 */
.seg-content {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.seg-content.expanded {
  display: block;
  overflow: visible;
  cursor: inherit;
}
.seg-content :deep(mark) {
  background: #fdf6ec;
  color: #b88230;
  padding: 0 2px;
  border-radius: 2px;
}

/* 底部分页固定 */
.drawer-footer {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid #e4e7ed;
}
</style>
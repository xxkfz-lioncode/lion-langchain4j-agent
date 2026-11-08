<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索问题 / 口径说明 / 涉及表"
          clearable
          style="width: 240px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
        <div class="spacer" />
        <el-button type="primary" plain @click="openCreate">
          <el-icon><Plus /></el-icon>新增示例
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="question" label="标准问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="sqlText" label="标准 SQL" min-width="240" show-overflow-tooltip />
        <el-table-column prop="tables" label="涉及表" width="150" show-overflow-tooltip />
        <el-table-column prop="explanation" label="口径说明" min-width="160" show-overflow-tooltip />
        <el-table-column prop="useCount" label="注入次数" width="90" />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑示例' : '新增示例'" width="680px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="标准问题" required>
          <el-input v-model="form.question" placeholder="用户会怎么问, 如 上个月各城市销售额" />
        </el-form-item>
        <el-form-item label="标准 SQL" required>
          <el-input v-model="form.sqlText" type="textarea" :rows="6" placeholder="该问题对应的标准 SQL" />
        </el-form-item>
        <el-form-item label="涉及表">
          <el-input v-model="form.tables" placeholder="逗号分隔, 如 biz_order" />
        </el-form-item>
        <el-form-item label="口径说明">
          <el-input v-model="form.explanation" type="textarea" :rows="2" placeholder="这条示例的口径/注意事项" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  listFewshots, createFewshot, updateFewshot, deleteFewshot
} from '@/api/text2sql'

const keyword = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

const emptyForm = () => ({
  id: null, question: '', sqlText: '', tables: '', explanation: '', enabled: 1
})
const form = ref(emptyForm())

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listFewshots({ keyword: keyword.value || undefined, page: page.value, size: size.value })
    // axios 拦截器已解包 R 信封, res 即 PageResult
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = emptyForm()
  dialogVisible.value = true
}

function openEdit(row) {
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.value.id) {
      await updateFewshot(form.value.id, form.value)
    } else {
      await createFewshot(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除示例 #${row.id} 吗?`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteFewshot(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; padding-top: 10px; }
</style>

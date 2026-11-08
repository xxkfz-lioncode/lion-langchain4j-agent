<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索术语 / 同义词 / 定义"
          clearable
          style="width: 240px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
        <div class="spacer" />
        <el-button type="primary" plain @click="openCreate">
          <el-icon><Plus /></el-icon>新增术语
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="term" label="术语" width="120" />
        <el-table-column prop="aliases" label="同义词" min-width="140" show-overflow-tooltip />
        <el-table-column prop="definition" label="业务定义" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tableName" label="主表" width="130" show-overflow-tooltip />
        <el-table-column prop="columnName" label="主字段" width="110" show-overflow-tooltip />
        <el-table-column prop="calcRule" label="计算口径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="filterRule" label="过滤条件" min-width="140" show-overflow-tooltip />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" width="90" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑术语' : '新增术语'" width="640px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="术语" required>
          <el-input v-model="form.term" placeholder="如 销售额" maxlength="64" />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input v-model="form.aliases" placeholder="逗号分隔, 如 销售金额,营收" />
        </el-form-item>
        <el-form-item label="业务定义">
          <el-input v-model="form.definition" type="textarea" :rows="2" placeholder="该术语的业务含义" />
        </el-form-item>
        <el-form-item label="主表 / 主字段">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input v-model="form.tableName" placeholder="主表, 如 biz_order" />
            <el-input v-model="form.columnName" placeholder="主字段, 如 amount" />
          </div>
        </el-form-item>
        <el-form-item label="计算口径">
          <el-input v-model="form.calcRule" type="textarea" :rows="2" placeholder="自然语言口径, 如 SUM(amount) 且排除已取消订单" />
        </el-form-item>
        <el-form-item label="过滤条件">
          <el-input v-model="form.filterRule" type="textarea" :rows="2" placeholder="附加 SQL 过滤片段, 如 status != 5" />
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
  listTerms, createTerm, updateTerm, deleteTerm
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
  id: null, term: '', aliases: '', definition: '',
  tableName: '', columnName: '', calcRule: '', filterRule: '', enabled: 1
})
const form = ref(emptyForm())

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listTerms({ keyword: keyword.value || undefined, page: page.value, size: size.value })
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
      await updateTerm(form.value.id, form.value)
    } else {
      await createTerm(form.value)
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
    await ElMessageBox.confirm(`确定删除术语「${row.term}」吗?`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteTerm(row.id)
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

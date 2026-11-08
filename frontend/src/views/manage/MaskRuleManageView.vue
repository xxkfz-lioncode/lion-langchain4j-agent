<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索表名 / 字段名"
          clearable
          style="width: 240px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
        <div class="spacer" />
        <el-button type="primary" plain @click="openCreate">
          <el-icon><Plus /></el-icon>新增规则
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="tableName" label="表名(*=所有表)" min-width="140" />
        <el-table-column prop="columnName" label="字段名(*=所有字段)" min-width="140" />
        <el-table-column prop="maskType" label="脱敏类型" width="110">
          <template #default="{ row }">
            <el-tag>{{ row.maskType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expression" label="自定义表达式" min-width="180" show-overflow-tooltip />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑规则' : '新增规则'" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="表名" required>
          <el-input v-model="form.tableName" placeholder="如 biz_order, * 表示所有表" />
        </el-form-item>
        <el-form-item label="字段名" required>
          <el-input v-model="form.columnName" placeholder="如 phone, * 表示所有字段" />
        </el-form-item>
        <el-form-item label="脱敏类型" required>
          <el-select v-model="form.maskType" style="width: 100%">
            <el-option label="PHONE 手机号" value="PHONE" />
            <el-option label="ID_CARD 身份证" value="ID_CARD" />
            <el-option label="EMAIL 邮箱" value="EMAIL" />
            <el-option label="NAME 姓名" value="NAME" />
            <el-option label="BANK_CARD 银行卡" value="BANK_CARD" />
            <el-option label="CUSTOM 自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.maskType === 'CUSTOM'" label="自定义表达式" required>
          <el-input
            v-model="form.expression"
            type="textarea"
            :rows="3"
            placeholder="SQL 表达式, 用 {col} 占位字段名, 如 CONCAT(LEFT({col},3),'****',RIGHT({col},4))"
          />
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
  listMaskRules, createMaskRule, updateMaskRule, deleteMaskRule
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
  id: null, tableName: '', columnName: '', maskType: 'PHONE', expression: '', enabled: 1
})
const form = ref(emptyForm())

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listMaskRules({ keyword: keyword.value || undefined, page: page.value, size: size.value })
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
      await updateMaskRule(form.value.id, form.value)
    } else {
      await createMaskRule(form.value)
    }
    ElMessage.success('保存成功, 规则即时生效')
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
    await ElMessageBox.confirm(`确定删除规则 #${row.id} (${row.tableName}.${row.columnName}) 吗?`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteMaskRule(row.id)
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

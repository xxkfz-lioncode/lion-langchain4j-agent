<template>
  <div class="user-page">
    <div class="user-card">
      <!-- 工具栏: 搜索 + 新增 -->
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="用户名 / 昵称"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>

      <!-- 用户表格 -->
      <el-table :data="records" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="nickname" label="昵称" min-width="140" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              :disabled="row.id === currentUserId"
              @change="(val) => handleToggleStatus(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" :icon="Key" @click="openResetPwd(row)">重置密码</el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="row.id === currentUserId"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        class="pager"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="fetchList"
        @current-change="fetchList"
      />
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入初始密码" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="请输入昵称(默认同用户名)" />
        </el-form-item>
        <el-form-item v-if="isEdit" label="头像" prop="avatar">
          <el-input v-model="form.avatar" placeholder="头像图片 URL(可留空)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="440px">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="80px">
        <el-form-item label="用户">
          <span>{{ pwdTarget.username }}</span>
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="handleResetPwd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, Key } from '@element-plus/icons-vue'
import { getInfo } from '@/api/auth'
import {
  pageUsers,
  createUser,
  updateUser,
  deleteUser,
  updateUserStatus,
  resetPassword
} from '@/api/user'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const currentUserId = ref(null)

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '' })

async function fetchList() {
  loading.value = true
  try {
    const page = (await pageUsers({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword
    })) || { total: 0, records: [] }
    records.value = page.records || []
    total.value = page.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.keyword = ''
  query.pageNum = 1
  fetchList()
}

// ---- 新增 / 编辑 ----
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, username: '', password: '', nickname: '', avatar: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '长度需在 3-32 之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, { id: null, username: '', password: '', nickname: '', avatar: '' })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row) {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    username: row.username,
    nickname: row.nickname,
    avatar: row.avatar || ''
  })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (isEdit.value) {
      await updateUser(form.id, {
        username: form.username,
        nickname: form.nickname,
        avatar: form.avatar
      })
      ElMessage.success('用户已更新')
    } else {
      await createUser({
        username: form.username,
        password: form.password,
        nickname: form.nickname
      })
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    fetchList()
  } finally {
    saving.value = false
  }
}

// ---- 状态切换 ----
async function handleToggleStatus(row, val) {
  const status = val ? 1 : 0
  try {
    await updateUserStatus(row.id, status)
    row.status = status
    ElMessage.success(status === 1 ? '已启用' : '已禁用')
  } catch (e) {
    // 失败时回滚开关并重新拉取
    fetchList()
  }
}

// ---- 重置密码 ----
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdFormRef = ref()
const pwdForm = reactive({ password: '' })
const pwdTarget = ref({ id: null, username: '' })
const pwdRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

function openResetPwd(row) {
  pwdTarget.value = { id: row.id, username: row.username }
  pwdForm.password = ''
  pwdVisible.value = true
  pwdFormRef.value?.clearValidate()
}

async function handleResetPwd() {
  await pwdFormRef.value.validate()
  pwdSaving.value = true
  try {
    await resetPassword(pwdTarget.value.id, pwdForm.password)
    ElMessage.success('密码已重置')
    pwdVisible.value = false
  } finally {
    pwdSaving.value = false
  }
}

// ---- 删除 ----
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${row.username}」吗? 删除后不可恢复。`, '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return // 用户取消
  }
  await deleteUser(row.id)
  ElMessage.success('用户已删除')
  // 若当前页删空则回退一页
  if (records.value.length === 1 && query.pageNum > 1) query.pageNum -= 1
  fetchList()
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getFullYear())}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  try {
    const info = await getInfo()
    currentUserId.value = info?.userId ?? null
  } catch (e) {
    // 忽略: 无法获取时禁用逻辑交由后端兜底
  }
  fetchList()
})
</script>

<style scoped>
.user-page {
  height: 100%;
}

.user-card {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  padding: 20px;
  min-height: 100%;
  box-sizing: border-box;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>

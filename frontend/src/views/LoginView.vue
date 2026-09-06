<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <h1>Lion Agent</h1>
        <p>LangChain4j + 千问 智能助手</p>
      </div>

      <div class="mode-tabs">
        <span
          class="mode-tab"
          :class="{ active: mode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </span>
        <span
          class="mode-tab"
          :class="{ active: mode === 'register' }"
          @click="switchMode('register')"
        >
          注册
        </span>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @keyup.enter="handleSubmit"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <template v-if="mode === 'register'">
          <el-form-item prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-form-item prop="nickname">
            <el-input
              v-model="form.nickname"
              placeholder="昵称(选填)"
              :prefix-icon="User"
              clearable
            />
          </el-form-item>
        </template>

        <el-form-item>
          <el-button
            type="primary"
            class="login-btn"
            :loading="loading"
            @click="handleSubmit"
          >
            {{ mode === 'login' ? '登 录' : '注 册' }}
          </el-button>
        </el-form-item>
      </el-form>

      <div v-if="mode === 'login'" class="login-tip">默认账号: admin / 123456</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login, register } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const mode = ref('login')
const form = reactive({ username: 'admin', password: '', confirmPassword: '', nickname: '' })

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度需在 3-32 之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码不能少于 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        value === form.password
          ? callback()
          : callback(new Error('两次输入的密码不一致'))
      },
      trigger: 'blur'
    }
  ]
}

const rules = computed(() => (mode.value === 'register' ? registerRules : loginRules))

function switchMode(target) {
  if (mode.value === target) return
  mode.value = target
  formRef.value?.clearValidate()
  form.password = ''
  form.confirmPassword = ''
  // 登录页预填的演示账号切到注册时清空, 避免与 admin 撞名
  if (target === 'register' && form.username === 'admin') {
    form.username = ''
  }
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    if (mode.value === 'register') {
      await register({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || undefined
      })
      ElMessage.success('注册成功, 正在自动登录...')
    }
    const data = await login({
      username: form.username.trim(),
      password: form.password
    })
    auth.setLogin({ token: data.token, nickname: data.nickname, role: data.role })
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/chat')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d4f 0%, #2d4a8f 50%, #3e6cc7 100%);
}

.login-card {
  width: 400px;
  padding: 40px 36px 28px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}

.login-brand {
  text-align: center;
  margin-bottom: 24px;
}

.login-brand h1 {
  margin: 0;
  font-size: 28px;
  color: #2d4a8f;
  letter-spacing: 1px;
}

.login-brand p {
  margin: 8px 0 0;
  color: #909399;
  font-size: 13px;
}

.mode-tabs {
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  margin-bottom: 24px;
}

.mode-tab {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  font-size: 15px;
  color: #909399;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: color 0.2s;
}

.mode-tab.active {
  color: #2d4a8f;
  font-weight: 600;
  border-bottom-color: #2d4a8f;
}

.login-btn {
  width: 100%;
}

.login-tip {
  margin-top: 4px;
  text-align: center;
  color: #c0c4cc;
  font-size: 12px;
}
</style>

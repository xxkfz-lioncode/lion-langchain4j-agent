<template>
  <view class="login-page">
    <view class="hero">
      <view class="hero-logo">L</view>
      <text class="hero-title">Lion Agent</text>
      <text class="hero-sub">智能助手, 随时待命</text>
    </view>

    <view class="card">
      <input
        v-model="username"
        class="field"
        placeholder="用户名"
        placeholder-class="field-ph"
        :disabled="loading"
      />
      <input
        v-model="password"
        class="field"
        password
        placeholder="密码"
        placeholder-class="field-ph"
        :disabled="loading"
        @confirm="onSubmit"
      />
      <button class="btn" :loading="loading" :disabled="loading" @click="onSubmit">
        登 录
      </button>
      <text v-if="error" class="error">{{ error }}</text>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function onSubmit() {
  if (loading.value) return
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await login({
      username: username.value.trim(),
      password: password.value
    })
    user.setLogin(result || {})
    uni.reLaunch({ url: '/pages/chat/chat' })
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background: linear-gradient(180deg, #2d4a8f 0%, #3e6cc7 32%, #f3f5f9 32%);
  padding: 0 30px;
  box-sizing: border-box;
}

.hero {
  padding-top: calc(var(--status-bar-height) + 13vh);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.hero-logo {
  width: 72px;
  height: 72px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.18);
  border: 2px solid rgba(255, 255, 255, 0.45);
  color: #fff;
  font-size: 36px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-title {
  margin-top: 14px;
  color: #fff;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
}

.hero-sub {
  margin-top: 6px;
  color: rgba(255, 255, 255, 0.75);
  font-size: 13px;
}

.card {
  margin-top: 34px;
  background: #fff;
  border-radius: 18px;
  padding: 28px 22px;
  box-shadow: 0 10px 30px rgba(45, 74, 143, 0.12);
}

.field {
  height: 48px;
  background: #f3f5f9;
  border-radius: 12px;
  padding: 0 16px;
  margin-bottom: 14px;
  font-size: 15px;
}

.field-ph {
  color: #b0b8c4;
}

.btn {
  margin-top: 8px;
  height: 48px;
  line-height: 48px;
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  color: #fff;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(45, 74, 143, 0.3);
}

.btn[disabled] {
  opacity: 0.6;
}

.error {
  display: block;
  margin-top: 12px;
  color: #e5484d;
  font-size: 13px;
  text-align: center;
}
</style>

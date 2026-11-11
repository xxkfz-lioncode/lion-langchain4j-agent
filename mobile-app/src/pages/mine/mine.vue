<template>
  <view class="page">
    <!-- 头部个人卡片 -->
    <view class="profile">
      <view class="avatar">{{ (user.nickname || '用户').charAt(0) }}</view>
      <view class="info">
        <text class="nickname">{{ user.nickname || '未登录' }}</text>
        <view class="role-chip">
          {{ user.role === 'admin' ? '管理员' : user.role === 'user' ? '普通用户' : user.role || '-' }}
        </view>
      </view>
    </view>

    <!-- 菜单 -->
    <view class="menu">
      <view class="menu-item" @click="showAbout">
        <text class="menu-ico">ℹ</text>
        <text class="menu-label">关于</text>
        <text class="menu-arrow">›</text>
      </view>
      <view class="menu-item" @click="onLogout">
        <text class="menu-ico logout">⏻</text>
        <text class="menu-label logout">退出登录</text>
      </view>
    </view>

    <text class="version">Lion Agent v1.0.0</text>
  </view>
</template>

<script setup>
import { onMounted } from 'vue'
import { logout } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const user = useUserStore()

onMounted(() => {
  if (!user.isLogin) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
})

function showAbout() {
  uni.showModal({
    title: '关于',
    content: 'Lion LangChain4j Agent 移动端 — 对话助手\n支持 H5 / 微信小程序 / App 三端',
    showCancel: false
  })
}

async function onLogout() {
  try {
    await new Promise((resolve, reject) => {
      uni.showModal({
        title: '提示',
        content: '确定要退出登录吗?',
        success: (res) => (res.confirm ? resolve() : reject(new Error('cancel')))
      })
    })
    logout().catch(() => {})
    user.clear()
    uni.reLaunch({ url: '/pages/login/login' })
  } catch (e) {
    // 用户取消
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f3f5f9;
  padding: 0 14px 20px;
  box-sizing: border-box;
}

/* 头部个人卡片: 渐变蓝底 */
.profile {
  margin: calc(var(--status-bar-height) + 14px) 0 14px;
  padding: 26px 20px;
  border-radius: 18px;
  background: linear-gradient(135deg, #3e6cc7, #2d4a8f);
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 8px 20px rgba(45, 74, 143, 0.25);
}

.avatar {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid rgba(255, 255, 255, 0.4);
}

.info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.nickname {
  font-size: 19px;
  font-weight: 600;
  color: #fff;
}

.role-chip {
  align-self: flex-start;
  font-size: 12px;
  color: #dbe6fb;
  background: rgba(255, 255, 255, 0.16);
  border-radius: 10px;
  padding: 3px 10px;
}

/* 菜单 */
.menu {
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.05);
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #f3f5f9;
}

.menu-item:active {
  background: #f8fafc;
}

.menu-item:last-child {
  border-bottom: none;
}

.menu-ico {
  font-size: 16px;
  color: #2d4a8f;
  width: 20px;
  text-align: center;
}

.menu-ico.logout {
  color: #e5484d;
}

.menu-label {
  flex: 1;
  font-size: 15px;
  color: #1f2937;
}

.menu-label.logout {
  color: #e5484d;
}

.menu-arrow {
  color: #c0c8d4;
  font-size: 18px;
}

.version {
  display: block;
  text-align: center;
  margin-top: 26px;
  color: #b0b8c4;
  font-size: 12px;
}
</style>

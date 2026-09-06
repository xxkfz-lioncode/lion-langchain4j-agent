<template>
  <div class="placeholder-page">
    <div class="placeholder-card">
      <el-empty :description="title + ' 功能开发中'" />
      <p class="placeholder-desc">{{ description }}</p>
      <el-button type="primary" plain @click="$router.push('/chat')">
        前往对话助手体验
      </el-button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '该页面' },
  description: { type: String, default: '该模块尚未开放, 敬请期待。' }
})
</script>

<style scoped>
.placeholder-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border-radius: 10px;
}

.placeholder-card {
  text-align: center;
}

.placeholder-desc {
  margin: 0 0 20px;
  color: #909399;
  font-size: 14px;
}
</style>

<template>
  <div class="home-page">
    <div class="home-hero">
      <h2>该页面为 Lion Agent 系统功能演示页面</h2>
      <p>对话助手: 基于 LangChain4j + 千问大模型, 支持多轮记忆、工具调用与流式输出。</p>
      <p>RAG 知识库: 上传文档自动切分、向量化入库, 对话时检索增强作答。</p>
    </div>

    <div class="home-stats">
      <div class="stat-card">
        <div class="stat-num">{{ stats.messages }}</div>
        <div class="stat-label">累计对话</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.users }}</div>
        <div class="stat-label">注册用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.chats }}</div>
        <div class="stat-label">会话总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.online }}</div>
        <div class="stat-label">在线人数</div>
      </div>
    </div>

    <div class="home-card">
      <div class="card-title">快速开始</div>
      <ul class="quick-list">
        <li>
          <el-icon><ChatDotRound /></el-icon>
          点击左侧「对话助手」, 进入 AI 智能对话页面开始提问。
        </li>
        <li>
          <el-icon><User /></el-icon>
          在「用户管理」中可以查看当前登录账号信息。
        </li>
        <li>
          <el-icon><Document /></el-icon>
          点击「接口文档」可直接在页面内查看并在线调试后端所有接口。
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ChatDotRound, User, Document } from '@element-plus/icons-vue'

const stats = ref({ messages: 0, users: 1, chats: 0, online: 1 })

onMounted(() => {
  // 占位数据; 如需真实统计可在此调用后端接口
})
</script>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.home-hero {
  background: linear-gradient(135deg, #2d4a8f 0%, #3e6cc7 100%);
  color: #fff;
  padding: 28px 32px;
  border-radius: 10px;
}

.home-hero h2 {
  margin: 0 0 14px;
  font-size: 22px;
}

.home-hero p {
  margin: 6px 0;
  line-height: 1.8;
  color: #dbe6f8;
  font-size: 14px;
}

.home-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  background: #fff;
  border-radius: 10px;
  padding: 22px 20px;
  text-align: center;
}

.stat-num {
  font-size: 30px;
  font-weight: 700;
  color: #2d4a8f;
}

.stat-label {
  margin-top: 6px;
  color: #909399;
  font-size: 13px;
}

.home-card {
  background: #fff;
  border-radius: 10px;
  padding: 24px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e8eaee;
}

.quick-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.quick-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  color: #606266;
  font-size: 14px;
}

.quick-list li .el-icon {
  color: #2d4a8f;
  font-size: 18px;
}
</style>

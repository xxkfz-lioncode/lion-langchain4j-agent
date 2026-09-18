<template>
  <div class="home-page">
    <!-- 顶部简介 -->
    <div class="home-hero">
      <h2>LangChain4j 知识总览</h2>
      <p>
        Lion Agent 基于 LangChain4j 1.20.0 + 千问大模型, 覆盖以下核心能力, 点击条目可跳转对应演示页面。
      </p>
    </div>

    <!-- 知识点条目列表 -->
    <div class="home-card">
      <div class="card-title">核心知识点（{{ items.length }} 项）</div>
      <ul class="item-list">
        <li
          v-for="item in items"
          :key="item.no"
          :class="{ clickable: item.link }"
          @click="item.link && $router.push(item.link)"
        >
          <span class="item-no">{{ item.no }}</span>
          <el-icon class="item-icon"><component :is="item.icon" /></el-icon>
          <span class="item-title">{{ item.title }}</span>
          <span class="item-desc">{{ item.desc }}</span>
          <span class="item-link" v-if="item.link">
            查看 <el-icon><Right /></el-icon>
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { markRaw } from 'vue'
import {
  ChatDotRound, Memo, Tools, Collection, Stamp,
  Connection, Box, Grid, MagicStick, Cpu, Right
} from '@element-plus/icons-vue'

// LangChain4j 知识点条目(按后端实现顺序)
const items = [
  {
    no: '01',
    title: 'AI Services 对话服务',
    desc: '注解式 @AiService 自动装配 + 编程式 AiServices.builder() 双方案, 支持 TokenStream 流式输出',
    icon: ChatDotRound,
    link: '/chat'
  },
  {
    no: '02',
    title: '会话记忆 Chat Memory',
    desc: 'ChatMemoryProvider 按登录用户隔离多轮上下文, 底层持久化到 MySQL, 重启自动恢复',
    icon: Memo,
    link: '/chat'
  },
  {
    no: '03',
    title: '工具调用 Tool Calling',
    desc: '@Tool 注解扫描与 ToolSpecification+ToolExecutor 编程注册双方案, 集成 Resilience4j 熔断降级',
    icon: Tools,
    link: '/chat'
  },
  {
    no: '04',
    title: 'RAG 检索增强',
    desc: '文档加载 → 段落切分 → 向量化入库 Milvus → 相似度检索注入上下文, 可用 lion.rag.enabled 开关',
    icon: Collection,
    link: '/rag'
  },
  {
    no: '05',
    title: '护栏 Guardrails',
    desc: '输入护栏按声明顺序串行(fatal 短路), 输出护栏支持 reprompt 自动重答(maxRetries 控制次数)',
    icon: Stamp,
    link: '/chat'
  },
  {
    no: '06',
    title: 'MCP 工具协议',
    desc: 'Streamable HTTP 与本地 stdio 子进程两种传输, McpToolProvider 暴露给模型按需调用',
    icon: Connection,
    link: '/mcp'
  },
  {
    no: '07',
    title: 'Skills 技能加载',
    desc: 'markdown 技能说明文档当作动态工具源, 模型按需读取获取操作指引, 与 MCP 工具合并注册',
    icon: Box,
    link: '/mcp'
  },
  {
    no: '08',
    title: '结构化输出',
    desc: '从自然语言中直接提取结构化 POJO, 接口返回值即 Java 对象, 框架自动生成提取提示词',
    icon: Grid,
    link: '/structured'
  },
  {
    no: '09',
    title: '文本分类',
    desc: '情感打标双引擎: LLM 分类器(理解语义)与 Embedding 分类器(向量相似度), 支持少样本示例',
    icon: MagicStick,
    link: '/sentiment'
  },
  {
    no: '10',
    title: '模型接入',
    desc: '千问 DashScope 走 OpenAI 兼容协议, 同一套配置装配 ChatModel / StreamingChatModel / EmbeddingModel',
    icon: Cpu,
    link: null
  }
].map(i => ({ ...i, icon: markRaw(i.icon) }))
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
  margin: 0 0 10px;
  font-size: 22px;
}

.home-hero p {
  margin: 0;
  line-height: 1.8;
  color: #dbe6f8;
  font-size: 14px;
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
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e8eaee;
}

.item-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.item-list li {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 8px;
  border-bottom: 1px dashed #f0f2f5;
  font-size: 14px;
}

.item-list li:last-child {
  border-bottom: none;
}

.item-list li.clickable {
  cursor: pointer;
  transition: background 0.15s;
}

.item-list li.clickable:hover {
  background: #f4f7fd;
}

.item-no {
  color: #b8c0cf;
  font-family: Consolas, monospace;
  font-size: 13px;
  width: 24px;
  flex-shrink: 0;
}

.item-icon {
  color: #2d4a8f;
  font-size: 17px;
  flex-shrink: 0;
}

.item-title {
  font-weight: 600;
  color: #303133;
  width: 190px;
  flex-shrink: 0;
}

.item-desc {
  color: #909399;
  flex: 1;
  font-size: 13px;
}

.item-link {
  display: flex;
  align-items: center;
  gap: 2px;
  color: #3e6cc7;
  font-size: 12.5px;
  font-weight: 600;
  flex-shrink: 0;
}
</style>

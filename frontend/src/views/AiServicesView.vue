<template>
  <div class="ai-services-page">
    <!-- 顶部简介 -->
    <div class="page-hero">
      <h2>AI Services 配置指南</h2>
      <p>
        AiServices 是 LangChain4j 的核心装配器: 定义一个接口, 动态代理出实现,
        把「模型 + 记忆 + RAG + 工具 + 护栏」组装成一句话调用的 AI Service。
        下表列出全部常用配置项的作用与用法, 均对应项目 ManualAssistant.java 的真实写法。
      </p>
    </div>

    <!-- 配置项总表 -->
    <div class="table-card">
      <div class="card-title">常用配置项一览</div>
      <el-table :data="configs" style="width: 100%" :row-style="{ cursor: 'default' }">
        <el-table-column label="配置项" width="230" fixed>
          <template #default="{ row }">
            <code class="cfg-name">{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="purpose" label="作用" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'must-tag': row.required }">
              {{ row.purpose }}
            </span>
            <el-tag v-if="row.required" size="small" type="danger" class="req-tag">必配</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="怎么用" min-width="340">
          <template #default="{ row }">
            <code class="cfg-usage">{{ row.usage }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="where" label="项目实现位置" width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cfg-where">{{ row.where }}</span>
          </template>
        </el-table-column>
        <el-table-column label="体验" width="80" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.link"
              size="small"
              type="primary"
              link
              @click="$router.push(row.link)"
            >
              查看 →
            </el-button>
            <span v-else class="cfg-none">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 使用要点 -->
    <div class="table-card">
      <div class="card-title">使用要点</div>
      <el-table :data="notes" style="width: 100%">
        <el-table-column label="要点" width="180">
          <template #default="{ row }">
            <span class="note-title">{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="500">
          <template #default="{ row }">
            <span class="note-desc">{{ row.desc }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 完整装配代码 -->
    <div class="table-card">
      <div class="card-title">项目完整装配代码（ManualAssistant.java）</div>
      <pre class="code-block"><code v-html="fullCode"></code></pre>
    </div>
  </div>
</template>

<script setup>
// ====================== 配置项总表 ======================

const configs = [
  {
    name: '.chatModel(model)',
    purpose: '阻塞式对话模型, 接口方法返回 String / POJO 时走它',
    usage: '.chatModel(chatModel)',
    where: 'assistant/ManualAssistant.java',
    link: '/chat'
  },
  {
    name: '.streamingChatModel(model)',
    purpose: '流式模型, 接口方法返回 TokenStream 时走它, 前端打字机效果',
    usage: '.streamingChatModel(streamingChatModel)  // 与 chatModel 可双开',
    where: 'assistant/ManualAssistant.java',
    link: '/chat'
  },
  {
    name: '.chatMemoryProvider(p)',
    purpose: '按 @MemoryId(登录用户)隔离的多轮记忆, 每人一份独立上下文',
    usage: '.chatMemoryProvider(chatMemoryProvider)  // 不配则每轮都是新对话',
    where: 'memory/UserChatMemoryProvider.java',
    link: '/chat'
  },
  {
    name: '.chatMemory(memory)',
    purpose: '单例记忆, 所有用户共享(仅演示用, 生产用 Provider)',
    usage: '.chatMemory(MessageWindowChatMemory.withMaxMessages(10))',
    where: '—',
    link: null
  },
  {
    name: '.contentRetriever(r)',
    purpose: 'RAG 检索增强: 每轮对话前用输入去向量库检索片段, 注入 prompt',
    usage: '.contentRetriever(contentRetriever)  // EmbeddingStoreContentRetriever(Milvus)',
    where: 'rag/config/RagConfig.java',
    link: '/rag'
  },
  {
    name: '.tools(...)',
    purpose: '静态工具: 启动时固定注册, @Tool 注解扫描或 spec -> executor 编程式',
    usage: '.tools(tools)  // 项目用编程式, 集成 Resilience4j 熔断降级',
    where: 'tools/programmatic/ProgrammaticToolsFactory.java',
    link: '/chat'
  },
  {
    name: '.toolProvider(p)',
    purpose: '动态工具: 每次请求时再决定给模型哪些工具',
    usage: '.toolProvider(skills.toolProvider())  // Skills 技能文档当工具源',
    where: 'skills/SkillsFactory.java',
    link: '/mcp'
  },
  {
    name: '.toolProviders(a, b)',
    purpose: '变参版本, 合并多个动态工具供给者, 取并集',
    usage: 'builder.toolProviders(skills.toolProvider(), mcpToolProvider)  // Skills + MCP',
    where: 'assistant/ManualAssistant.java',
    link: '/mcp'
  },
  {
    name: '.inputGuardrails(...)',
    purpose: '输入护栏: 用户输入 → 模型前校验, 按声明顺序串行, fatal 短路',
    usage: '.inputGuardrails(new GuardrailB(), new GuardrailA(), new LoggingInputGuardrail())',
    where: 'guardrail/GuardrailA.java 等',
    link: '/chat'
  },
  {
    name: '.outputGuardrails(...)',
    purpose: '输出护栏: 模型输出 → 返回前校验, 失败可 reprompt 重答',
    usage: '.outputGuardrails(new LoggingOutputGuardrail())',
    where: 'guardrail/LoggingOutputGuardrail.java',
    link: '/chat'
  },
  {
    name: '.outputGuardrailsConfig(c)',
    purpose: '输出护栏重答次数: maxRetries(N) = 总共 N 次尝试',
    usage: '.outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(2).build())',
    where: 'assistant/ManualAssistant.java',
    link: '/chat'
  },
  {
    name: '.build()',
    purpose: '生成接口的动态代理, 即最终可注入使用的 AI Service',
    usage: 'ProgrammaticAssistant assistant = builder.build();',
    where: 'assistant/ManualAssistant.java',
    link: null
  }
]

// ====================== 使用要点 ======================

const notes = [
  {
    title: '模型至少配一个',
    desc: 'chatModel 与 streamingChatModel 至少开一个, 否则调用接口时报错; 双开时同一接口同时支持阻塞与流式方法。'
  },
  {
    title: '记忆 vs 记忆 Provider',
    desc: 'chatMemory 是单例(所有人共享, 演示用); chatMemoryProvider 按 memoryId 隔离, 生产标配。项目按登录用户隔离, 底层 MySQL 持久化, 重启不丢。'
  },
  {
    title: '静态工具 vs 动态工具',
    desc: 'tools() 启动时固定; ToolProvider 每次请求动态供给。模型看到的是两者合集。项目: 编程式静态工具 + Skills/MCP 两套动态工具合并。'
  },
  {
    title: '护栏顺序即执行顺序',
    desc: 'inputGuardrails 按声明顺序串行执行(项目中故意 B 在 A 前, 演示这一点); 前面返回 fatal 直接短路, 后面的不再执行。'
  },
  {
    title: 'maxRetries 语义',
    desc: 'maxRetries(2) = 总共 2 次尝试(即失败后自动重答 1 次), 第 2 次仍失败抛异常给调用方。注意是「总共 N 次」不是「重试 N 次」。'
  },
  {
    title: '注解式等价方案',
    desc: 'Spring Boot 项目可用注解式: @AiService 标接口 + @InputGuardrails / @OutputGuardrails 标护栏, 免写 builder(见 AnnotatedAssistant.java)。'
  },
  {
    title: '一轮请求的执行顺序',
    desc: '组装记忆历史 → RAG 检索增强 → 输入护栏(调用 LLM 前的最后一步, 能看到 RAG 增强后的消息, fatal 则不调 LLM) → 模型调用(需工具则执行后回填再调) → 输出护栏(在包括工具在内的所有操作之后执行, 可 retry/reprompt) → 返回。关键: 输入护栏在 RAG 之后执行, 不是之前。'
  }
]

// ====================== 完整代码 ======================

const rawCode = `AiServices<ProgrammaticAssistant> builder = AiServices.builder(ProgrammaticAssistant.class)
        .chatModel(chatModel)                    // 阻塞对话用
        .streamingChatModel(streamingChatModel)  // chatStream 用
        .chatMemoryProvider(chatMemoryProvider)  // 按 memoryId(登录用户)隔离多轮上下文
        .contentRetriever(contentRetriever)      // RAG 检索增强
        .tools(tools)                            // 编程式注册工具: spec -> executor
        // 输入护栏(按声明顺序串行执行: B → A → Logging; 前面 fatal 会短路后面)
        .inputGuardrails(new GuardrailB(), new GuardrailA(), new LoggingInputGuardrail())
        // 输出护栏(工具调用完成后, reprompt/fatal 由 LoggingOutputGuardrail.validate() 决定)
        .outputGuardrails(new LoggingOutputGuardrail())
        // 失败时最多自动重答 1 次 (maxRetries=2 = 总共 2 次尝试)
        .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(2).build());

// 合并 Skills + MCP 两个动态 ToolProvider(取并集)
// MCP 子进程关停(lion.mcp.enabled=false)时 mcpToolProvider 为 null, 此时只挂 Skills
if (mcpToolProvider != null) {
    builder.toolProviders(skills.toolProvider(), mcpToolProvider);
} else {
    builder.toolProvider(skills.toolProvider());
}

this.assistant = builder.build();`

/** 轻量语法高亮 */
const highlight = (line) => {
  const commentIdx = line.indexOf('//')
  const code = commentIdx >= 0 ? line.slice(0, commentIdx) : line
  const comment = commentIdx >= 0 ? line.slice(commentIdx) : ''
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  let html = esc(code)
    .replace(/"[^"]*"/g, (m) => `<span class="tok-str">${m}</span>`)
    .replace(/\.(\w+)\(/g, '.<span class="tok-m">$1</span>(')
  if (comment) {
    html += `<span class="tok-c">${esc(comment)}</span>`
  }
  return html
}

const fullCode = rawCode.split('\n').map(highlight).join('\n')
</script>

<style scoped>
.ai-services-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-hero {
  background: linear-gradient(135deg, #2d4a8f 0%, #3e6cc7 100%);
  color: #fff;
  padding: 28px 32px;
  border-radius: 10px;
}

.page-hero h2 {
  margin: 0 0 10px;
  font-size: 22px;
}

.page-hero p {
  margin: 0;
  line-height: 1.8;
  color: #dbe6f8;
  font-size: 14px;
}

.table-card {
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

.cfg-name {
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: #2d4a8f;
  background: #eef3fd;
  padding: 3px 8px;
  border-radius: 4px;
  white-space: nowrap;
}

.cfg-usage {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #606266;
  background: #f6f8fa;
  padding: 2px 6px;
  border-radius: 3px;
  word-break: break-all;
}

.cfg-where {
  font-size: 12.5px;
  color: #909399;
}

.cfg-none {
  color: #c0c4cc;
}

.req-tag {
  margin-left: 6px;
}

.must-tag {
  font-size: 13px;
  color: #606266;
}

.note-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #303133;
}

.note-desc {
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
}

/* ---- 代码块 ---- */
.code-block {
  margin: 0;
  background: #1e2533;
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  line-height: 1.7;
  color: #d8dee9;
  white-space: pre;
}

:deep(.tok-c) {
  color: #7d8aa0;
  font-style: italic;
}

:deep(.tok-m) {
  color: #8fc7ff;
}

:deep(.tok-str) {
  color: #c3e88d;
}
</style>

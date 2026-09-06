# Lion LangChain4j Agent

一个可迭代的 AI Agent 演示工程：**Vue3 前端 + Spring Boot 3 后端**，后端集成
**MyBatis-Plus / Sa-Token / Redisson / LangChain4j(千问 Qwen)**。当前已实现
「登录 + 多轮对话(SSE 流式) + 会话记忆持久化 + 工具调用(Tool Calling)」，
后续可在此基础上继续迭代。

## 技术栈

| 端 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite 6 + Pinia + Vue Router + Axios + Element Plus |
| 后端 | Java 17 + Spring Boot 3.4 + MyBatis-Plus + Sa-Token + Redisson + LangChain4j 0.36.2 |
| AI 模型 | 通义千问(DashScope, OpenAI 兼容协议) |
| 数据库 | MySQL 8.x |

## 目录结构

```
lion-langchain4j-agent/
├── pom.xml                 # 后端 Maven 工程(即后端工程本体)
├── .env / .env.example     # 环境变量(敏感配置), .env 已 gitignore
├── application.yml         # 非敏感配置, 敏感值仅以 ${...} 引用 .env
├── docs/sql/init.sql       # 建库建表脚本(含 chat_message 会话消息表)
├── start-backend.bat       # 后端一键启动
├── start-all.bat           # 前后端一键启动
├── src/main/java/com/lion/agent/
│   ├── config/             # Sa-Token / Redisson / MyBatis-Plus / CORS / .env 加载
│   ├── common/             # Result / 异常 / 全局异常处理
│   ├── controller/         # AuthController / ChatController(SSE)
│   ├── service/            # 业务层 + AgentAssistant(@AiService 接口)
│   ├── tools/              # AgentTools(@Tool 工具集, Spring Bean)
│   ├── memory/             # 会话记忆: Provider + MySQL ChatMemoryStore
│   └── entity|mapper|dto
└── frontend/               # 前端工程(Vue3)
    ├── .env                # VITE_API_BASE_URL 等
    ├── start.bat           # 前端一键启动
    └── src/views/          # 登录页 + 对话页(marked 渲染 AI Markdown)
```

## 环境要求

- JDK 17+、Maven 3.8+
- Node.js 18+、npm
- MySQL 8.x（默认账号密码 `root/123456` 可改）
- Redis（可选，未开启 Redis 也不影响启动；Redisson 客户端已就绪，token 持久化可接入）

## 快速开始

1. **初始化数据库**：执行 `docs/sql/init.sql`（自动建库、建表并插入默认账号
   `admin / 123456`）。

2. **配置环境变量**：复制 `.env.example` 为 `.env`，填写：
   - `DB_PASSWORD`：MySQL 密码
   - `QWEN_API_KEY`：阿里云百炼(DashScope)的 API Key（必填，否则对话不可用）
   - 其余保持默认即可

   > 敏感信息只放 `.env`，不写入任何 yml。

3. **启动后端**（根目录）：`start-backend.bat` 或 `mvn spring-boot:run`，
   启动后监听 `http://localhost:8080`。

4. **启动前端**（`frontend` 目录）：`start.bat`（首次自动 `npm install`）。
   访问 `http://localhost:5173`，用 `admin / 123456` 登录后即可对话。

> 根目录的 `start-all.bat` 可一键同时打开前后端两个窗口。

## 配置说明(.env)

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| SERVER_PORT | 后端端口 | 8080 |
| DB_HOST / DB_PORT / DB_NAME / DB_USERNAME / DB_PASSWORD | MySQL 连接 | 127.0.0.1:3306 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DATABASE | Redis(Redisson) | 127.0.0.1:6379 |
| QWEN_API_KEY | 千问(DashScope)密钥 | 必填 |
| QWEN_BASE_URL | OpenAI 兼容地址 | dashscope compatible-mode |
| QWEN_MODEL_NAME | 模型名 | qwen-plus(可换 qwen-max/qwen-turbo) |

## 已实现接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录, 返回 satoken |
| POST | /api/auth/logout | 登出 |
| GET | /api/auth/info | 当前用户信息 |
| POST | /api/chat/send | 阻塞式对话 {message, conversationId?} -> {reply} |
| GET | /api/chat/stream | **SSE 流式对话**, 逐 token 推送, 结束发 `[DONE]` |
| POST | /api/chat/clear | 清空当前会话上下文 `?conversationId=` |
| GET | /api/chat/conversations | 当前用户的会话列表(按更新时间倒序) |
| POST | /api/chat/conversations | 新建会话(默认标题"新对话") |
| DELETE | /api/chat/conversations/{id} | 删除会话(同时清理其消息与记忆) |
| GET | /api/chat/messages | 某会话的历史消息 `?conversationId=` |

除 `/api/auth/login` 外，`/api/**` 均需在请求头携带 `satoken`。

---

# LangChain4j 知识点与实战(本工程沉淀)

> 以下知识点均基于 **LangChain4j 0.36.2**（`dev.langchain4j:langchain4j-spring-boot-starter`
> + `langchain4j-open-ai-spring-boot-starter`），结合本工程真实代码归纳。

## 1. 整体架构：AiServices 自动装配

LangChain4j 的「智能助手」是一个 **代理(Agent)对象**：把 模型 + 记忆 + 工具 + 系统提示
组装在一起。本工程用 **Spring Starter 注解式** 方式，只需要声明一个接口：

```java
@AiService(                      // langchain4j-spring 自动生成代理 Bean
        chatModel = "openAiChatModel",              // 阻塞模型 Bean 名
        streamingChatModel = "openAiStreamingChatModel", // 流式模型 Bean 名
        tools = "agentTools"                        // 工具集 Spring Bean 名
)
public interface AgentAssistant {
    @SystemMessage(fromResource = "prompts/agent-system.txt")
    String chat(@MemoryId String memoryId, @UserMessage("{{message}}") String message);

    @SystemMessage(fromResource = "prompts/agent-system.txt")
    TokenStream chatStream(@MemoryId String memoryId, @UserMessage("{{message}}") String message);
}
```

要点：
- 模型 Bean（`openAiChatModel` / `openAiStreamingChatModel`）由
  `langchain4j-open-ai-spring-boot-starter` 依据 yml 自动装配，**必须按 Bean 名引用**；
- 工具集写成一个 `@Component`，用 `@Tool("描述")` 标注方法，大模型按需自主调用；
- **方法返回类型决定走哪条链路**：返回 `String` -> 阻塞 `chatModel`；
  返回 `TokenStream` -> 流式 `streamingChatModel`。同一个接口里两种都提供，
  对应「问答」与「打字机」两种交互。

## 2. 千问(DashScope)接入：OpenAI 兼容协议

DashScope 提供 OpenAI 兼容端点，因此直接复用 `langchain4j-open-ai`，只需改
`base-url` 与 `api-key`：

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${QWEN_API_KEY:}
      base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      model-name: ${QWEN_MODEL_NAME:qwen-plus}
      temperature: 0.7
      max-tokens: 2048
    streaming-chat-model:          # 流式必须单独配置一份!
      api-key: ${QWEN_API_KEY:}
      base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      model-name: ${QWEN_MODEL_NAME:qwen-plus}
```

## 3. 流式对话链路(SSE)：后端完整实现

链路：**前端 fetch SSE** -> `ChatController.stream`(SseEmitter) -> `ChatServiceImpl.chatStream`
-> `AgentAssistant.chatStream`(AiService 代理) -> `OpenAiStreamingChatModel` 逐 token 回调。

```java
// Controller: 声明 text/event-stream, SseEmitter 不设超时(0L)
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam("message") String message) {
    SseEmitter emitter = new SseEmitter(0L);
    TokenStream tokenStream = chatService.chatStream(memoryId, message);
    tokenStream
            .onNext(partial -> send(emitter, partial))      // 每个 token 推一次
            .onComplete(response -> { send(emitter, "[DONE]"); emitter.complete(); })
            .onError(emitter::completeWithError)
            .start();                                       // 必须 start() 才发起请求
    return emitter;
}
```

要点：
- **0.36 版回调 API 是 `onNext` / `onComplete` / `onError`**（0.35 之前的版本叫
  `onPartialResponse` / `onComplete` / `onError`，不要混用）；
- 记得调用 `.start()`，否则请求不会发出；
- 结束标记：后端推 `data: [DONE]`，前端据此判定一轮回复结束；
- SSE 无法复用统一的 JSON `Result` 包装，每个事件直接承载文本。

## 4. 会话记忆：按用户隔离 + MySQL 持久化

记忆由三块协作：

| 组件 | 作用 | 本工程实现 |
| --- | --- | --- |
| `ChatMemoryProvider` | 按 `memoryId` 返回记忆实例 | `UserChatMemoryProvider` |
| `ChatMemory`(窗口) | 保留最近 N 条消息 | `MessageWindowChatMemory.maxMessages(20)` |
| `ChatMemoryStore` | 消息读写底层存储 | `MySqlChatMemoryStore`(chat_message 表) |

```java
// Provider: memoryId 通常就是 "chat:" + 登录用户id, 同用户多轮不丢上下文
@Override
public ChatMemory get(Object memoryId) {
    return memories.computeIfAbsent(memoryId, id ->
            MessageWindowChatMemory.builder()
                    .id(id)
                    .maxMessages(20)
                    .chatMemoryStore(chatMemoryStore)
                    .build());
}
```

- `@MemoryId` 参数负责把当前会话路由到对应记忆；
- `AiServices` 会在每次对话前后**自动**把消息写入记忆(无需手动拼历史)；
- MySQL 落库技巧：用 `ChatMessageSerializer.messageToJson()` 序列化整条消息(含
  工具调用等复杂消息)存 `message_json` 字段，读取时用 `ChatMessageDeserializer
  .messageFromJson()` 还原；因窗口是全量最新状态，采用「先删后插」保证一致性。

## 5. 工具调用(Tool Calling)

```java
@Component
public class AgentTools {
    @Tool("获取当前的日期和时间, 返回格式为 yyyy-MM-dd HH:mm:ss")
    public String currentTime() { ... }

    @Tool("计算两个整数的和")
    public int add(int a, int b) { ... }
}
```

- `@Tool` 的**描述文本会作为模型决定是否调用该工具的依据**，务必写清楚；
- 工具调用过程(模型出参 -> 执行 -> 结果回填)由框架自动完成，代码里无需手工编排；
- 记忆里专门用 `ToolExecutionResultMessage` 承载工具结果，序列化方案已支持。

## 6. 模型日志：log-requests / log-responses 为什么「不生效」

LangChain4j 0.36 的 `log-requests` / `log-responses` **不是由 langchain4j 自己的
logger 输出**，而是透传给底层的 OpenAI HTTP 客户端(openai4j 的 OkHttp 拦截器，
`ResponseLoggingInterceptor` / `RequestLoggingInterceptor`)打印，logger 包名为
**`dev.ai4j.openai4j`**。所以只调 `logging.level.dev.langchain4j: debug` 没用：

```yaml
logging:
  level:
    dev.ai4j.openai4j: debug   # 关键: 必须开这个包才能看到请求/响应日志
```

日志能证明流请求确实发出并收到 200 + `text/event-stream`(响应体是流式，拦截器会
打印 `skipping response body due to streaming`)。

## 7. 前端：AI 回复的 Markdown 渲染

AI 输出是 Markdown，前端直接插文本会「挤成一坨」。方案：`marked` 转 HTML +
`DOMPurify` 消毒后再 `v-html` 渲染(防 XSS)：

```js
import { marked } from 'marked'
import DOMPurify from 'dompurify'

function renderMd(text = '') {
  const html = marked.parse(String(text || ''), { breaks: true, gfm: true })
  return DOMPurify.sanitize(html)
}
// <div v-if="item.role === 'assistant'" v-html="renderMd(item.content) + (item.streaming ? CURSOR_HTML : '')"></div>
```

要点：
- `v-html` 注入的标签**不在 Vue 组件的 scoped 样式作用域内**，标题/列表/代码块等
  排版样式必须写在非 scoped 的 `<style>` 块(用 `.markdown-body` 前缀)；
- 打字机光标也随 `v-html` 注入(如 `<span class="stream-cursor">`)，样式同样放全局；
- **新增 npm 依赖后必须重启 Vite dev server**，否则 `import 'marked'` 解析失败，
  页面仍渲染旧代码(表现为 Markdown 始终不生效)。

## 8. 常见坑清单(踩坑记录)

| 现象 | 根因 / 结论 |
| --- | --- |
| `streamingChatModel cannot be null` | 流式接口需要 `streaming-chat-model`，只配 `chat-model` 不够，yml 里必须单独配置(见第 2 节) |
| `log-requests/responses` 无输出 | 日志在 `dev.ai4j.openai4j` 包，不是 `dev.langchain4j`(见第 6 节) |
| `IllegalArgumentException: response cannot be null`(ChatModelResponseContext) | DashScope(OpenAI 兼容)+ 流式 + 带 tools 时，流结束事件被转成空 response。0.36.2 已知缺陷，见第 9 节 |
| `No converter for [Result] with preset Content-Type 'text/event-stream'` | SSE 响应里抛异常后，全局异常处理器仍想写 JSON `Result` 导致二次异常；SSE 出错路径不应返回统一 JSON 包装 |
| Markdown 始终显示 `###`/`**` 原文 | 前端未真正应用 marked 渲染：新增依赖未重启 Vite / v-html 未接入(见第 7 节) |

## 9. 已知缺陷与升级路线

### DashScope 流式空 response 缺陷

- 复现环境：langchain4j 0.36.2 + OpenAI 兼容端点 + 流式 + 配置了 tools；
- 报错：`java.lang.IllegalArgumentException: response cannot be null`
  (`ChatModelResponseContext.<init>`，openai4j `StreamingRequestExecutor` SSE 线程)；
- 社区同类问题：langchain4j issue #2289(0.36.2 + OpenAI 兼容 + 流式 + tools，
  2024-12 已关闭，修复随 0.37.x 发布)；issue #2864(Qwen/DashScope 相关场景在
  1.0.0-beta1 仍复现且官方标记不修)。
- 处置建议(按顺序尝试)：
  1. **升级 langchain4j 到 0.37.x+**：pom 中改 `<langchain4j.version>` 即可，
     本工程用到的 `@AiService` / `TokenStream.onNext` / yml 属性在 0.37 保持兼容；
  2. 若升级后仍偶发，考虑**去掉流式路径上的 tools**，或对空增量做忽略；
  3. 彻底绕开方案：改用 DashScope 原生模块 `QwenStreamingChatModel`
     (1.0 起为 `dev.langchain4j:langchain4j-community-dashscope`，
     spring 属性前缀 `langchain4j.community.dashscope.chat-model`)，不走
     openai4j 的 OpenAI 兼容 SSE 解析。

### 版本升级提醒(0.36.2 -> 1.x)

- 1.0 起 DashScope 模块迁移为 `langchain4j-community-dashscope`；
- `@AiService` 等 Spring 集成、AiService 装配 API 有调整，升级需回归验证
  「阻塞/流式双链路 + 记忆 + 工具」。

## 迭代方向(待办)

- [x] 登录(BCrypt) + 注册 + Sa-Token 鉴权
- [x] 对话 SSE 流式输出、会话记忆持久化(MySQL)、系统人设 Prompt、工具调用
- [ ] 接入/排查 DashScope 流式空 response 缺陷(见第 9 节), 升级 LangChain4j
- [ ] 会话记录入库查询/管理界面、多模型切换

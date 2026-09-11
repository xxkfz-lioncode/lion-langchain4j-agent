# LangChain4j 0.36.2 → 1.20.0 升级记录与代码修改点

> 项目：`lion-langchain4j-agent`（Spring Boot 3 + MyBatis-Plus + Sa-Token + Redisson + LangChain4j / 千问 DashScope）
> 升级日期：2026-09-05
> 升级范围：langchain4j 0.36.2 → **1.20.0**（核心）/ **1.20.0-beta30**（Spring Boot Starter），Spring Boot **3.4.1 → 3.5.13**

---

## 0. 变更统计摘要

| 统计维度 | 数量 | 说明 |
|---|---|---|
| 依赖版本变更 | 2 处 | Spring Boot 3.4.1→3.5.13；langchain4j 0.36.2→1.20.0-beta30 |
| 传递依赖连带更新 | 3 个 | langchain4j core / open-ai / http-client-spring-restclient（见 §1） |
| 代码修改文件 | 6 个 | pom.xml、application.yml、AgentAssistant、ChatController、ChatServiceImpl、MySqlChatMemoryStore |
| 代码修改点 | **14 处** | 详见 §3 编号 P1~P14 |
| 破坏性 API 适配 | **3 类** | TokenStream 回调更名、ChatMessage.text() 移除、@AiService 需显式装配 |
| 数据兼容处理 | 1 处 | 0.36(Gson) 旧 message_json → 1.x(Jackson) 解析容错 |
| 新增文件 | 1 个 | 本文档 |
| 前端（frontend/）改动 | 0 | SSE 协议与 REST 契约未变 |

---

## 1. 版本升级概要

| 组件 | 原版本 | 新版本 | 备注 |
|---|---|---|---|
| Spring Boot（parent） | 3.4.1 | **3.5.13** | langchain4j-spring Starter beta30 内部依赖 Boot 3.5.13，必须对齐 |
| langchain4j 核心 | 0.36.2 | **1.20.0** | 稳定版，由 spring-boot-starter 传递引入 |
| langchain4j-spring-boot-starter | 0.36.2 | **1.20.0-beta30** | 官方 Starter 版本线（见 §1.1） |
| langchain4j-open-ai-spring-boot-starter | 0.36.2 | **1.20.0-beta30** | 社区 OpenAI 兼容实现，对接千问 DashScope |
| langchain4j-open-ai | 0.36.x | **1.20.0** | 由 open-ai starter 传递引入 |
| langchain4j-http-client-spring-restclient | 无 | **1.20.0-beta30** | 1.x 新增（取代 0.x 的 openai4j 自有 HTTP 客户端） |
| Java | 17 | 17 | 不变 |
| MyBatis-Plus / Sa-Token / Redisson | 3.5.7 / 1.46.0 / 3.38.1 | 不变 | 风险提示见 §8.2 |

### 1.1 版本线说明（重要）

langchain4j 1.x 起，官方 **Spring Boot Starter 走 `X.Y.0-betaZ` 预发布版本线**，与核心库 `X.Y.0` **同日配套发布**，并非"不稳定测试版"：

- `langchain4j:1.20.0`（核心，稳定版）
- `langchain4j-spring-boot-starter:1.20.0-beta30`（官方配套 Starter）
- `langchain4j-open-ai-spring-boot-starter:1.20.0-beta30`（依赖社区模块 `langchain4j-open-ai:1.20.0`）

两个 starter 均显式依赖 `spring-boot-starter:3.5.13`，因此本工程 Spring Boot parent 必须同步升到 3.5.13，否则出现 Boot 3.4/3.5 依赖混用。

---

## 2. 修改文件总览

| # | 文件 | 修改点数 | 修改类型 |
|---|---|---|---|
| 1 | `pom.xml` | 2（P1、P2） | 依赖版本升级 + 注释更新 |
| 2 | `src/main/resources/application.yml` | 1（P3） | 日志配置清理 |
| 3 | `AgentAssistant.java` | 4（P4~P7） | @AiService 显式装配（破坏性适配） |
| 4 | `ChatController.java` | 2（P8、P9） | TokenStream 回调更名（破坏性适配） |
| 5 | `ChatServiceImpl.java` | 3（P10~P12） | ChatMessage.text() 移除适配（破坏性适配） |
| 6 | `MySqlChatMemoryStore.java` | 4（P13~P14 + 2） | text() 适配 + Gson→Jackson 数据容错 |

> 注：项目当前**未启用 git 版本控制**，无法自动生成 diff 行数统计；以下修改点基于 0.36.2 / 1.20.0 两版 API 逐一比对核对得出。

---

## 3. 代码修改点明细（P1 ~ P14）

### 3.1 `pom.xml`（2 个修改点）

**P1｜Spring Boot parent 版本对齐**
```xml
<!-- 修改前 -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.1</version>
    <relativePath/>
</parent>

<!-- 修改后：与 langchain4j-spring 1.20.0-beta30 配套的 Spring Boot 版本 -->
<parent>
    ...
    <version>3.5.13</version>
    ...
</parent>
```
原因：langchain4j-spring-boot-starter / open-ai starter beta30 显式依赖 `spring-boot-starter:3.5.13`，parent 不同步升会导致 Boot 3.4 与 3.5 自动配置类混用。

**P2｜langchain4j 版本属性**
```xml
<!-- 修改前 -->
<langchain4j.version>0.36.2</langchain4j.version>

<!-- 修改后：1.x 核心为稳定版, Starter 采用配套 beta 版本线(与核心同日发布) -->
<langchain4j.version>1.20.0-beta30</langchain4j.version>
```
连带更新了 `<properties>` 与两个 starter 依赖块上方的注释（说明 1.20.0 / open-ai 模块传递关系）。两个 starter 的 groupId/artifactId **未变**。

### 3.2 `application.yml`（1 个修改点）

**P3｜删除已废弃的 openai4j 日志配置**
```yaml
# 修改前
logging:
  level:
    dev.ai4j.openai4j: debug   # openai4j 在 1.x 已删除

# 修改后
logging:
  level:
    com.lion.agent: info
    # langchain4j 1.x 已移除 openai4j(dev.ai4j.openai4j), 模型请求/响应日志由 langchain4j OpenAI
    # 模块通过 log-requests/log-responses 配置控制; 需要排查时可将 dev.langchain4j 临时调为 debug
    dev.langchain4j: warn
```
**确认未动**：`langchain4j.open-ai.chat-model.*` / `streaming-chat-model.*` 配置前缀与全部属性在 1.x 兼容，Bean 名仍为 `openAiChatModel` / `openAiStreamingChatModel`。

### 3.3 `AgentAssistant.java`（4 个修改点）

**P4｜新增 import**
```java
// 新增
import dev.langchain4j.service.spring.AiServiceWiringMode;
```

**P5｜@AiService 增加 wiringMode（核心破坏性适配）**
```java
// 修改前：0.36 默认即按 bean 名装配
@AiService(
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        tools = "agentTools"
)

// 修改后：1.x 必须显式 EXPLICIT 才按 bean 名装配
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "userChatMemoryProvider",
        tools = "agentTools"
)
```
说明：1.x `@AiService` 新增 `wiringMode`，默认 `AUTOMATIC`（自动装配容器中唯一同类型 Bean；存在多个同类型 Bean 时启动即抛 "Conflict" 异常）。本工程同时存在 ChatModel / StreamingChatModel / ChatMemoryProvider / 工具等多类 Bean，故改为 `EXPLICIT` 按 Bean 名注入（对应 `AiServicesAutoConfig#addBeanReference` 逻辑）。

**P6｜新增 chatMemoryProvider 显式指定**
升级前若不加该属性，1.x 中会话记忆不会按 `@MemoryId` 隔离，`clear()` 也会失效。显式指向 `UserChatMemoryProvider` 的默认 Bean 名 `userChatMemoryProvider`。

**P7｜类注释更新**
补充装配说明（EXPLICIT 模式下的 bean 名清单）、阻塞/流式路由规则说明。`@SystemMessage(fromResource=...)` / `@UserMessage("{{message}}")` / `@MemoryId` / `TokenStream` 返回值用法**未变**（`fromResource` 资源在 classpath 根 `/prompts/agent-system.txt` 可被回退加载）。

### 3.4 `ChatController.java`（2 个修改点）

**P8｜TokenStream 流式回调更名（破坏性适配）**
```java
// 修改前：0.36 API
tokenStream
        .onNext(partial -> send(emitter, partial))
        .onComplete(response -> {
            send(emitter, STREAM_DONE);
            emitter.complete();
        })
        .onError(emitter::completeWithError)
        .start();

// 修改后：1.x API（onError / start 不变）
tokenStream
        .onPartialResponse(partial -> send(emitter, partial))
        .onCompleteResponse(response -> {
            send(emitter, STREAM_DONE);
            emitter.complete();
        })
        .onError(emitter::completeWithError)
        .start();
```
1.x 中 `onNext` → `onPartialResponse`、`onComplete` → `onCompleteResponse`（参数为 `ChatResponse`，本接口未使用其内容）。

**P9｜注释更新**
方法 javadoc 同步补充 langchain4j 1.x 回调 API 说明。

### 3.5 `ChatServiceImpl.java`（3 个修改点）

**P10｜新增 import**
```java
import dev.langchain4j.data.message.ToolExecutionResultMessage; // 新增
```

**P11｜listMessages 取文本调用替换**
```java
// 修改前：ChatMessage 接口级 text()（0.36 存在）
list.add(new MessageVO(roleOf(message), message.text()));

// 修改后：委托新私有方法按子类取文本
list.add(new MessageVO(roleOf(message), textOf(message)));
```

**P12｜新增 textOf(ChatMessage) 私有方法（破坏性适配核心）**
1.x 中 `ChatMessage` 接口仅保留 `type()`，`text()` 移到子类且行为不一致：
- `UserMessage.text()` **已删除** → 用 `hasSingleText()` + `singleText()`
- `SystemMessage.text()` / `AiMessage.text()` 保留
- `ToolExecutionResultMessage.text()` 仅单文本可用，否则抛 `IllegalStateException`
```java
/** 提取消息纯文本用于展示(与 MySqlChatMemoryStore.contentOf 保持一致); 非单文本/无文本时返回 null */
private String textOf(ChatMessage message) {
    if (message instanceof UserMessage userMessage) {
        return userMessage.hasSingleText() ? userMessage.singleText() : null;
    } else if (message instanceof AiMessage aiMessage) {
        return aiMessage.text();
    } else if (message instanceof ToolExecutionResultMessage toolMessage) {
        try {
            return toolMessage.text();
        } catch (IllegalStateException e) {
            return null; // 多内容(非纯文本)消息, 不用于展示
        }
    }
    return null;
}
```
（`roleOf` 同步补充 `ToolExecutionResultMessage → "tool"` 分支；`chat`/`chatStream`/`clear` 逻辑未变。）

### 3.6 `MySqlChatMemoryStore.java`（4 个修改点）

**P13｜类注解与 import**
- 新增 `@Slf4j`（import `lombok.extern.slf4j.Slf4j`），用于数据容错告警日志
- 新增 import `dev.langchain4j.data.message.ToolExecutionResultMessage`

**P14｜contentOf 按子类取文本（同上 P12 适配）**
```java
/** 提取消息文本用于展示(1.x 中 ChatMessage 无统一 text(), 需按类型取); 非单文本/无文本时返回 null */
private String contentOf(ChatMessage message) {
    if (message instanceof UserMessage userMessage) {
        return userMessage.hasSingleText() ? userMessage.singleText() : null;
    } else if (message instanceof SystemMessage systemMessage) {
        return systemMessage.text();
    } else if (message instanceof AiMessage aiMessage) {
        return aiMessage.text();
    } else if (message instanceof ToolExecutionResultMessage toolMessage) {
        try {
            return toolMessage.text();
        } catch (IllegalStateException e) {
            return null; // 多内容(非纯文本)消息
        }
    }
    return null;
}
```

**P15｜getMessages 增加旧数据反序列化容错（数据兼容，非编译问题）**
0.36 默认 **Gson** 序列化 `message_json`，1.20.0 默认 **Jackson**（`JacksonChatMessageJsonCodec`），新旧 JSON 不互通。首次读取旧数据可能抛异常导致整轮对话失败，改为单条 try-catch 跳过并 warn：
```java
for (ChatMessageEntity row : rows) {
    try {
        messages.add(ChatMessageDeserializer.messageFromJson(row.getMessageJson()));
    } catch (RuntimeException e) {
        // 兼容性兜底: langchain4j 0.36(Gson) 升级到 1.x(Jackson) 后旧 message_json 可能无法解析,
        // 跳过该条历史记录, 下一次对话全量覆写时会自然清理掉
        log.warn("跳过无法解析的历史消息 id={}, memoryId={}, 原因: {}", row.getId(), key, e.getMessage());
    }
}
```
`updateMessages`（先删后插全量覆写）在首次新对话成功后会把该 memoryId 的旧坏数据整体替换为 1.x 格式，**无需人工清表**。

**P16｜接口签名兼容确认（无需改动）**
`ChatMemoryStore` 三个核心抽象方法签名（`getMessages` / `updateMessages` / `deleteMessages`）在 1.x 未变；新增 async 系列为带默认实现的方法，同步路径不受影响。

---

## 4. API 兼容性核对表（0.36.2 → 1.20.0）

| 代码/配置 | 0.36.2 | 1.20.0 | 修改点 | 是否需要改 |
|---|---|---|---|---|
| `TokenStream.onNext / onComplete` | ✅ | ❌ 移除 | P8 | 是 |
| `TokenStream.onPartialResponse / onCompleteResponse` | ❌ | ✅ 新增 | P8 | 是 |
| `TokenStream.onError / start` | ✅ | ✅ 不变 | - | 否 |
| `ChatMessage.text()`（接口级） | ✅ | ❌ 移除 | P11/P12/P14 | 是 |
| `UserMessage.text()` | ✅ | ❌ → `hasSingleText()`/`singleText()` | P12/P14 | 是 |
| `@AiService` bean 名装配 | ✅ 默认 | 需 `wiringMode = EXPLICIT` | P5 | 是 |
| `@AiService.chatMemoryProvider` | ❌ 无 | ✅ 新增 | P6 | 是 |
| `ChatMemoryStore` 三核心方法 | ✅ | ✅ 签名不变 | P16 | 否 |
| `MessageWindowChatMemory.builder().id/maxMessages/chatMemoryStore` | ✅ | ✅ 不变 | - | 否 |
| `ChatMemoryProvider.get(Object)` | ✅ | ✅ 不变 | - | 否 |
| `ChatMessageSerializer.messageToJson` / `ChatMessageDeserializer.messageFromJson` | ✅ | ✅ 方法签名不变（默认 codec：Gson→Jackson） | P15 | 否（数据容错） |
| `@Tool`（`dev.langchain4j.agent.tool`） | ✅ | ✅ 包路径与 `@Tool("描述")` 不变 | - | 否 |
| `@SystemMessage/@UserMessage/@MemoryId` 模板 | ✅ | ✅ 不变（fromResource 支持 classpath 根回退） | - | 否 |
| 配置前缀 `langchain4j.open-ai.chat-model/streaming-chat-model` | ✅ | ✅ 不变 | - | 否 |
| Bean 名 `openAiChatModel` / `openAiStreamingChatModel` | ✅ | ✅ 不变 | - | 否 |

---

## 5. 本次升级未触碰的文件（确认无需改动）

| 文件 | 说明 |
|---|---|
| `AgentTools.java` | `@Tool` 注解用法与包路径 1.x 完全兼容 |
| `UserChatMemoryProvider.java` | `ChatMemoryProvider` 接口、`MessageWindowChatMemory` builder 方法均不变 |
| `ChatService.java`（接口） | 仅引用 `TokenStream`，包路径未变 |
| `prompts/agent-system.txt` | 系统提示模板资源，`fromResource` 加载兼容 |
| `ChatMessageEntity` / `ChatMessageMapper` | 纯业务持久化层，不依赖 langchain4j API |
| `AuthService` / `ConversationService` / `UserService` | 不依赖 langchain4j API |
| 前端 `frontend/` | SSE 协议（`data: token` / `[DONE]`）与 REST 接口契约未变 |

---

## 6. 升级收益

- **千问流式对话缺陷修复**：0.x 社区 OpenAI 兼容实现偶发的流式输出不完整 / `response cannot be null` 问题，属 0.x 对 DashScope OpenAI 兼容协议适配缺陷，1.x 已修复。
- **多模态消息模型**：1.x 消息采用 `Content` 列表模型（文本/图片等多模态），为后续接入多模态能力铺路。
- **HTTP 客户端统一**：1.x 统一走 langchain4j 自己的 HTTP 客户端抽象（Spring RestClient 实现），不再依赖已停止维护的 openai4j。

---

## 7. 验证与后续事项

### 7.1 待办：IDE 内重新构建验证
命令行构建环境为 JDK 8，无法编译 Java 17 工程。请在 IDEA 中执行 **Maven Reload + Build**（首次将下载全部 1.x 依赖），并跑通冒烟用例：登录 → 发送消息 → 流式回复完整 → 刷新页面回放历史 → 清空会话 → 删除会话。

### 7.2 风险：第三方库与 Spring Boot 3.5 兼容性
Spring Boot 由 3.4.1 升至 3.5.13 属配套升级。工程内版本较旧的第三方库（MyBatis-Plus 3.5.7、Sa-Token 1.46.0）原则上兼容 Boot 3.5，但若启动期出现自动配置类报错，需同步升级：
- `com.baomidou:mybatis-plus-spring-boot3-starter`（建议升到 3.5.9+）
- `cn.dev33:sa-token-spring-boot3-starter` / `sa-token-redisson`（建议升到 1.4x 最新）

### 7.3 风险：历史 `chat_message` 数据
见 §3.6 P15：0.36 格式的历史消息读取时被跳过（warn 日志），首次对话后自动覆写为新格式。如需完整保留旧会话可回放，升级前可先 SQL 备份 `chat_message` 表。

### 7.4 多模型扩展注意事项
当前 `@AiService` 用 `EXPLICIT` 模式，后续新增第二个 `ChatModel` Bean 不影响本接口装配（按 Bean 名注入）；反之若改用默认 `AUTOMATIC`，出现多个同类型 Bean 时启动将抛 "Conflict: multiple beans" 异常。

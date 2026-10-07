# 兜兜X（DoudouX）

> 一个 Android 原生 AI 聊天客户端，可以接入 LLM 的 API。
> 后端接任意 **OpenAI 兼容接口**，同时兼容 **Ollama 原生 NDJSON**；
> 会话数据全部留在本地，上下文由客户端全权维护。

**这个项目的核心是 Agent Runtime，聊天界面只是它的载体**——
SSE 流式协议解析、Function Calling 多轮状态机、分层上下文预算与滚动压缩。
网络层基于 `HttpURLConnection`，JSON 基于 Android 自带的 `org.json`，
所有协议细节和调度逻辑都是逐行实现的。

---

## 目录

- [1. 项目定位](#1-项目定位)
- [2. 快速开始](#2-快速开始)
- [3. 整体架构](#3-整体架构)
- [4. AI 引擎层：一次请求的完整生命周期](#4-ai-引擎层一次请求的完整生命周期)
  - [4.1 统一引擎抽象](#41-统一引擎抽象)
  - [4.2 请求体构造](#42-请求体构造)
  - [4.3 连接与超时策略](#43-连接与超时策略)
  - [4.4 SSE 流式解析](#44-sse-流式解析)
  - [4.5 Ollama 原生 NDJSON 兼容](#45-ollama-原生-ndjson-兼容)
  - [4.6 结束判定与错误分支](#46-结束判定与错误分支)
  - [4.7 原始响应与 usage 提取](#47-原始响应与-usage-提取)
- [5. Function Calling：多轮工具调用](#5-function-calling多轮工具调用)
  - [5.1 工具声明](#51-工具声明)
  - [5.2 流式分片合并](#52-流式分片合并)
  - [5.3 多轮循环](#53-多轮循环)
  - [5.4 三道闸门与诊断日志](#54-三道闸门与诊断日志)
  - [5.5 轮次上限与降级](#55-轮次上限与降级)
- [6. 上下文工程：预算分层与滚动压缩](#6-上下文工程预算分层与滚动压缩)
  - [6.1 动机](#61-动机)
  - [6.2 分层预算模型](#62-分层预算模型)
  - [6.3 token 估算](#63-token-估算)
  - [6.4 按组裁剪算法](#64-按组裁剪算法)
  - [6.5 保护窗口与截断](#65-保护窗口与截断)
  - [6.6 滚动压缩](#66-滚动压缩)
  - [6.7 摘要提示词设计](#67-摘要提示词设计)
  - [6.8 延迟一轮生效的取舍](#68-延迟一轮生效的取舍)
  - [6.9 会话 key 与持久化](#69-会话-key-与持久化)
  - [6.10 完整调用链](#610-完整调用链)
- [7. 记忆系统：三层记忆](#7-记忆系统三层记忆)
  - [7.1 三层划分的依据](#71-三层划分的依据)
  - [7.2 记忆条目与遗忘评分](#72-记忆条目与遗忘评分)
  - [7.3 冲突消解](#73-冲突消解)
  - [7.4 抽取：只记该记的](#74-抽取只记该记的)
  - [7.5 检索：字符 bigram Jaccard](#75-检索字符-bigram-jaccard)
  - [7.6 注入上下文的位置](#76-注入上下文的位置)
  - [7.7 完整流程](#77-完整流程)
- [8. 流式日志 StreamLogger](#8-流式日志-streamlogger)
- [9. Markdown 渲染](#9-markdown-渲染)
- [10. 数据与配置](#10-数据与配置)
- [11. 已知坑与解决方案](#11-已知坑与解决方案)
- [12. 设计取舍](#12-设计取舍)
- [13. 路线图](#13-路线图)
- [14. 附录：关键代码索引](#14-附录关键代码索引)

---

## 1. 项目定位

### 项目速览

| 维度 | 说明 |
|---|---|
| 类型 | Android 原生 AI 聊天客户端 |
| 语言 | 纯 Java（JDK 17 语法） |
| 包名 | `com.doudou.x` |
| 后端 | 任意 OpenAI 兼容接口，或 Ollama 原生接口 |
| 数据 | 全部存本地 `SharedPreferences`，会话状态由客户端持有 |
| 规模 | 约 7900 行 Java，其中 AI / 上下文 / 记忆相关约 3600 行 |

### 三个值得一看的技术点

1. **SSE / NDJSON 流式解析**：一条事件只解析一次 JSON，增量、结束原因、工具分片一次取出；
   同时兼容 OpenAI 的 SSE 与 Ollama 的 NDJSON，两条分支共用同一套状态累积逻辑。
2. **Function Calling 多轮状态机**：工具调用的分片合并、本地执行、结果回灌、递归下一轮，
   全部在引擎内部闭环，UI 只负责展示。
3. **上下文工程**：分层预算 + 按组裁剪 + 滚动压缩。长对话按优先级裁剪，
   超出部分压成结构化摘要，且只压增量。
4. **三层记忆系统**：情景 / 语义 / 程序三类记忆各有独立的半衰期，
   抽取、冲突消解、遗忘淘汰、按需召回形成闭环——对话因此能跨会话"记住"用户。

---

## 2. 快速开始

### 环境

| 项 | 版本 |
|---|---|
| JDK | 25 |
| Gradle | 9.8.0（wrapper 已配好，直接用 `./gradlew`） |
| Android Gradle Plugin | 9.3.3 |
| compileSdk / minSdk / targetSdk | 35 / 24 / 35 |

### 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 配置一个接口

打开 App → 侧边栏 → **设置 → AI 接口设置**，填三项即可：

| 字段 | 说明 | 示例 |
|---|---|---|
| Base URL | 接口根地址，尾部斜杠会被自动去掉 | `https://api.openai.com/v1` |
| API Key | 以 `Bearer` 方式放进请求头 | `sk-...` |
| Model | 模型名 | `gpt-4o-mini` |

配置项是**多套并存**的（`ApiProfile` + `active_id`），可以随手切换、重命名、删除（至少保留 1 套）。

**接口三项填全之前，App 会走 `MockAiEngine`**——本地生成逐字流式回复，
用来体验打字机效果和 Markdown 渲染。

### 可选参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| System Prompt | 空 | 留空时请求体省略 system 消息 |
| Temperature | 留空 | 留空时该字段省略，取服务端默认 |
| Top P | 留空 | 同上 |
| 最大输出 Token | 0（跟随服务端上限） | **注意**：留空即取服务端默认（Ollama 为 1024），见 [10.1](#101-ollama-的-1024-天花板) |
| 发送完整对话历史 | 开 | 关掉则每轮只发当前这一句 |
| 关闭模型思考 | 关 | 下发 `reasoning_effort=none` |
| 启用 Function Calling | 关 | 依赖模型能力，按需开启 |

---

## 3. 整体架构

### 分层

```
┌─────────────────────────────────────────────────────────┐
│  UI 层  ui/                                             │
│  MainActivity · ChatAdapter · Markdown 渲染三件套        │
│  SettingsActivity · ApiSettingsActivity                 │
└───────────────────────┬─────────────────────────────────┘
                        │  AiEngine.StreamCallback（主线程回调）
┌───────────────────────▼─────────────────────────────────┐
│  引擎层  ai/                                             │
│  AiEngine(接口) ─ OpenAiEngine ─ MockAiEngine            │
│  ToolRegistry · ToolExecutor                            │
│  StreamLogger（日志）                                    │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│  上下文层  ai/context/                                   │
│  ContextManager ─ ContextAssembler ─ RollingCompressor   │
│  ContextBudget · TokenEstimator · SummaryStore           │
└───────────────────────┬─────────────────────────────────┘
                        │ 召回（同步）/ 抽取（异步）
┌───────────────────────▼─────────────────────────────────┐
│  记忆层  ai/memory/                                      │
│  MemoryManager ─ MemoryRetriever ─ MemoryExtractor       │
│  MemoryStore · MemoryItem · MemoryType                   │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│  数据层  data/ + model/                                  │
│  ApiConfigStore · ConversationStore · UiSettingsStore    │
│  ChatMessage · Conversation · ApiProfile · ToolCall      │
└─────────────────────────────────────────────────────────┘
```

### 目录树

```
app/src/main/java/com/doudou/x/
├── ai/                           引擎层
│   ├── AiEngine.java             接口 + StreamCallback
│   ├── OpenAiEngine.java         ★ 核心：请求、SSE 解析、工具调用循环
│   ├── MockAiEngine.java         未配接口时的本地模拟回复
│   ├── ToolRegistry.java         工具声明（tools[] JSON Schema）+ 执行
│   ├── ToolExecutor.java         工具执行器接口
│   ├── StreamLogger.java         流式日志整理
│   ├── context/                  ★ 上下文工程（预算分层 + 滚动压缩）
│   └── memory/                   ★ 三层记忆（情景 / 语义 / 程序）
│       ├── ContextManager.java       门面，对引擎只暴露一个方法
│       ├── ContextAssembler.java     分层预算 + 按组裁剪
│       ├── ContextBudget.java        分层配额
│       ├── ContextMessage.java       面向请求体的扁平条目
│       ├── ContextPlan.java          组装结果 + 统计
│       ├── TokenEstimator.java       token 估算
│       ├── RollingCompressor.java    滚动压缩
│       └── SummaryStore.java         摘要与压缩进度持久化
├── data/
│   ├── ApiConfigStore.java       多套接口配置
│   ├── ConversationStore.java    会话持久化
│   ├── SessionManager.java       登录态
│   └── UiSettingsStore.java      界面偏好
├── model/
│   ├── ChatMessage.java          单条消息
│   ├── Conversation.java         会话
│   ├── ApiProfile.java           一套接口配置 + 模型参数
│   └── ToolCall.java             一次工具调用
└── ui/
    ├── chat/                     主页、适配器、Markdown 渲染
    ├── settings/ login/ splash/
```

### 一次对话发生了什么

```
MainActivity.onSendClicked()
  ├─ 新建 user ChatMessage → adapter.addMessage
  ├─ 新建占位 ai ChatMessage(streaming=true) → adapter.addMessage
  └─ pickEngine().streamReply(historySnapshot, callback)
        │
        ├─ ApiConfigStore.isReady() ? OpenAiEngine : MockAiEngine
        │
        └─ OpenAiEngine.doRequest(history, callback, roundsLeft=3, carried=[])
              ├─ buildRequestBody(history, withTools)
              │     └─ ContextManager.buildMessages(...)   ← 上下文裁剪/摘要
              ├─ HttpURLConnection POST，SSE 逐行读
              │     "data: {...}" → parseEvent / mergeToolCallDeltas
              │     非 data 行且以 { 开头 → parseNativeChunk（Ollama 兜底）
              └─ 结束判定
                    ├─ HTTP 非 2xx      → notifyError（带服务端 message）
                    ├─ 完全没内容        → notifyError("响应内容为空"/"流意外中断")
                    ├─ 有 tool_calls     → 本地执行 → 拼 role=tool → 递归下一轮
                    └─ 否则             → notifyComplete
```

UI 侧有 80ms 的节流刷新，`ChatAdapter` 还额外走一条「流式快速通道」——
流式期间只 `setText`，Markdown 等到生成结束后统一解析，视图结构保持稳定。

---

## 4. AI 引擎层：一次请求的完整生命周期

这一章是全文的重点。所有代码都来自 `app/src/main/java/com/doudou/x/ai/OpenAiEngine.java`。

### 4.1 统一引擎抽象

引擎只有一个接口，真实引擎和模拟引擎实现同一套回调，上层按配置切换。

```java
public interface AiEngine {

    interface StreamCallback {
        /** 第一个 token 到来前调用（"思考中"阶段结束）。 */
        void onStart();

        /**
         * 流式增量。
         * @param fullText     到目前为止累计的正式回答
         * @param thinkingText 到目前为止累计的思考过程（可能为 null / 空）
         */
        void onToken(String fullText, String thinkingText);

        /** 正常结束。 */
        void onComplete(String fullText, String thinkingText, String rawResponse);

        /** 模型发起了工具调用，并且已经在本地执行完成。 */
        void onToolCall(List<ToolCall> calls);

        /** 请求失败。 */
        void onError(String errorMessage, String rawResponse);
    }

    /** 流式回答。回调始终发生在主线程。 */
    void streamReply(List<ChatMessage> history, StreamCallback callback);

    /** 停止当前输出。 */
    void cancel();
}
```

几个设计点：

- **`onToken` 传的是累计全文**。UI 侧即使因为节流丢掉几次回调，
  下一次拿到的依然是完整全文，内容始终对齐。代价是每次多传一些字节，换来的是状态简单。
- **回调一律 post 到主线程**（`mainHandler.post`），引擎内部的工作线程与 UI 完全隔离。
- **`cancel()` 是协作式的**：置 `cancelled` 标志 + `disconnect()`，
  循环每一轮都会检查标志，退出时走正常收尾路径。

引擎的线程模型很朴素——单线程池：

```java
private final ExecutorService executor = Executors.newSingleThreadExecutor();

@Override
public void streamReply(final List<ChatMessage> history, final StreamCallback callback) {
    cancel();
    cancelled = false;
    executor.execute(new Runnable() {
        @Override
        public void run() {
            doRequest(history, callback, MAX_TOOL_ROUNDS, new ArrayList<ToolCall>());
        }
    });
}
```

单线程就够了：一次对话同时只会有一个请求在跑，工具调用的续轮也是在这个线程里递归完成的。

### 4.2 请求体构造

#### 四个关键常量

```java
private static final int CONNECT_TIMEOUT_MS = 30000;
/** 流式读取超时：0 表示不限制。 */
private static final int READ_TIMEOUT_MS = 0;
/** Function Calling 最多自动接续的轮数。 */
private static final int MAX_TOOL_ROUNDS = 3;
/** 原始响应最多保留的字符数。 */
private static final int MAX_RAW_CHARS = 1024 * 1024;
```

#### 选择要回传的历史

```java
// 服务端不保存会话：完整历史由客户端每次回传；
// 关闭该开关时只发送当前这一句，用于省流或单轮问答场景
List<ChatMessage> toSend = history;
if (!config.isSendFullHistory() && history != null) {
    ChatMessage lastUser = null;
    for (int i = history.size() - 1; i >= 0; i--) {
        if (history.get(i).getRole() == ChatMessage.ROLE_USER) {
            lastUser = history.get(i);
            break;
        }
    }
    if (lastUser != null) {
        toSend = new ArrayList<>();
        toSend.add(lastUser);
    }
}
```

注意这里**从后往前找最后一条 user 消息**——
因为 `history` 的最后一个元素很可能是占位中的 AI 消息。

#### 组装请求体

`messages` 部分已经全部交给上下文模块（见[第 6 章](#6-上下文工程预算分层与滚动压缩)），
引擎侧只剩参数拼装：

```java
String systemPrompt = config.getSystemPrompt();
// 分层预算、历史裁剪与滚动摘要都在 ai.context 模块内，这里只取组装结果
JSONArray messages = contextManager.buildMessages(toSend, systemPrompt, withTools);

JSONObject body = new JSONObject();
body.put("model", config.getModel());
body.put("stream", true);
body.put("messages", messages);

// 模型参数：未设置的不下发，交给服务端默认值
float temperature = config.getTemperature();
if (temperature >= 0f) {
    body.put("temperature", temperature);
}
float topP = config.getTopP();
if (topP >= 0f) {
    body.put("top_p", topP);
}
int maxTokens = config.getMaxTokens();
if (maxTokens > 0) {
    body.put("max_tokens", maxTokens);
}
// 关闭模型思考：用 reasoning_effort=none（think=false 对部分服务端不生效）
if (config.isDisableThinking()) {
    body.put("reasoning_effort", "none");
}
// Function Calling：声明可用工具，交给模型决定要不要调用
if (withTools) {
    body.put("tools", ToolRegistry.toolsJson());
    body.put("tool_choice", "auto");
}
```

#### 字段下发规则

| 字段 | 下发条件 | 为什么 |
|---|---|---|
| `model` | 总是 | — |
| `stream` | 总是 `true` | 全程走流式 |
| `messages` | 总是 | 由上下文模块生成 |
| `temperature` | `>= 0` | 负数代表"用户留空"，该字段整体省略以取服务端默认 |
| `top_p` | `>= 0` | 同上 |
| `max_tokens` | `> 0` | 0 代表"跟随服务端上限" |
| `reasoning_effort` | 开关打开时 | 值为 `"none"` |
| `tools` | 三道闸门全过（见 5.4） | 同时下发 `tool_choice: "auto"` |

**为什么要区分"留空"和"零值"**：如果直接把 0 下发给服务端，
`temperature=0` 是让模型完全确定性输出，与"用户留空"是截然不同的语义。
所以 `ApiProfile` 里用 `VALUE_UNSET = -1f` 和 `MAX_TOKENS_UNLIMITED = 0` 两套哨兵值分开表达。

### 4.3 连接与超时策略

```java
HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
activeConnection = conn;
conn.setRequestMethod("POST");
conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
conn.setReadTimeout(READ_TIMEOUT_MS);
conn.setDoOutput(true);
conn.setRequestProperty("Content-Type", "application/json");
conn.setRequestProperty("Accept", "text/event-stream");
conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
```

**读超时设为 0（由用户主动决定终点）是这个项目踩过坑之后的结果。**

思考型模型在正式回答之前，可能长时间只吐 reasoning 内容，甚至完全静默几十秒。
之前固定 60s 会在思考进行到一半时就判成超时，用户看到的是"请求失败"，
但实际上服务端一切正常、只是还在想。放开时长之后，需要中断时用界面上的「停止」按钮主动断开即可。

配套的兜底：原始响应用 `MAX_RAW_CHARS`（1MB）截断累积，
给超长思考过程留出内存上限。

```java
/** 累积一行原始数据。超出上限后只做标记，避免把内存吃光。 */
private void appendRaw(StringBuilder raw, String line) {
    if (raw.length() >= MAX_RAW_CHARS) {
        return;
    }
    raw.append(line).append('\n');
    if (raw.length() >= MAX_RAW_CHARS) {
        raw.append("…（原始响应过长，已截断）\n");
    }
}
```

### 4.4 SSE 流式解析

#### 一条事件解析一次

早期版本把"取增量"、"取结束原因"、"收集工具分片"拆成三个方法，
每个方法都各自 `new JSONObject(payload)` 解析一遍，同一个 payload 被解析三四次。
现在合并成一次，解析结果放进一个 `ParsedEvent`：

```java
/**
 * 一条事件解析后的全部可用信息。
 * 把原来分散的 parseDelta / parseFinishReason / collectToolCallDeltas
 * 合并成一次解析：既能为日志提供一条事件的完整元信息，
 * 也省掉同一个 JSONObject 被反复构造的开销。
 */
private static final class ParsedEvent {
    /** 正式回答增量，无增量为空串。 */
    String answer = "";
    /** 思考过程增量，无增量为空串。 */
    String reasoning = "";
    /** 服务端结束原因（stop / length / tool_calls）。 */
    String finishReason;
    /** 本条事件携带的 tool_calls 分片数量。 */
    int toolCallDeltas;
    /** 本条事件的 tool_calls 原始数组。 */
    JSONArray toolCallArray;
}
```

#### 解析实现

```java
/** 解析一条 SSE 事件正文；非 JSON 或没有 choices 时返回 null。 */
private ParsedEvent parseEvent(String payload) {
    if (payload == null || payload.isEmpty()) {
        return null;
    }
    JSONObject obj;
    try {
        obj = new JSONObject(payload);
    } catch (Exception e) {
        return null;
    }
    JSONArray choices = obj.optJSONArray("choices");
    if (choices == null || choices.length() == 0) {
        return null;
    }
    JSONObject choice = choices.optJSONObject(0);
    if (choice == null) {
        return null;
    }
    ParsedEvent event = new ParsedEvent();
    String reason = choice.optString("finish_reason", null);
    if (reason != null && !reason.isEmpty() && !"null".equals(reason)) {
        event.finishReason = reason;
    }
    JSONObject delta = choice.optJSONObject("delta");
    if (delta != null) {
        event.answer = optText(delta, "content");
        // 兼容思考型模型：reasoning（Ollama / Qwen3）与 reasoning_content（DeepSeek）
        event.reasoning = optText(delta, "reasoning");
        if (event.reasoning.isEmpty()) {
            event.reasoning = optText(delta, "reasoning_content");
        }
        JSONArray calls = delta.optJSONArray("tool_calls");
        if (calls != null) {
            event.toolCallDeltas = calls.length();
            event.toolCallArray = calls;
        }
    }
    return event;
}

/** 取字符串字段，把 JSON null 与字段缺失统一成空串。 */
private static String optText(JSONObject obj, String key) {
    String value = obj.optString(key, null);
    return value == null || "null".equals(value) ? "" : value;
}
```

两个细节：

1. **`optText` 把三种情况统一成空串**——字段被省略、字段为 JSON `null`、字段为字符串 `"null"`。
   `org.json` 的 `optString` 在字段值是 JSON null 时会返回字符串 `"null"`，
   这个坑一旦放过，界面上会真的显示出一个 `null`。
2. **思考字段兼容两种命名**：`reasoning`（Ollama / Qwen3）和 `reasoning_content`（DeepSeek），
   先取前者，为空再取后者。

#### 主循环

```java
BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8));
String line;
boolean done = false;
final SparseArray<ToolCallBuilder> toolBuilders = new SparseArray<>();
while ((line = reader.readLine()) != null) {
    if (cancelled) {
        return;
    }
    boolean blank = line.trim().isEmpty();
    appendRaw(raw, line);
    // 原始事件交给日志类按批整理；blank 行没有信息量，日志里不出现
    if (!blank) {
        logger.appendRaw(line);
    }
    if (!line.startsWith("data:")) {
        // 兼容 Ollama 原生接口（/api/chat、/api/generate）直接返回的 NDJSON
        String jsonLine = line.trim();
        if (jsonLine.startsWith("{")) {
            String[] nativeDelta = parseNativeChunk(jsonLine);
            // ... 见 4.5
        }
        continue; // 跳过 event:、注释、空行
    }
    String payload = line.substring(5).trim();
    if ("[DONE]".equals(payload)) {
        done = true;
        break;
    }
    ParsedEvent event = parseEvent(payload);
    if (event == null) {
        logger.onEvent(payload, true, "", "", null, 0);
        logger.onParseFailure(payload);
        continue;
    }
    if (event.finishReason != null) {
        finishReason = event.finishReason;
    }
    // 先累计字数再登记事件：onEvent 可能因为满块而立即打印，
    // 那时计数必须已经包含本条增量，否则块的"累计N字"会滞后一块
    logger.countAnswer(event.answer.length());
    logger.countReasoning(event.reasoning.length());
    logger.onEvent(payload, true, event.answer, event.reasoning,
            event.finishReason, event.toolCallDeltas);
    mergeToolCallDeltas(event, toolBuilders);
    boolean changed = false;
    if (!event.answer.isEmpty()) {
        answer.append(event.answer);
        changed = true;
    }
    if (!event.reasoning.isEmpty()) {
        reasoning.append(event.reasoning);
        changed = true;
    }
    if (changed) {
        notifyToken(callback, answer.toString(), reasoning.toString());
    }
}
reader.close();
```

**思考过程和正式回答是两个独立的 `StringBuilder`**——
分开累积，界面上才能把"思考中"和正式回答分开渲染，
最后 `notifyComplete` 也是分开回传的。

**空行跳过**：SSE 事件之间的空行只作分隔，直接跳过。

### 4.5 Ollama 原生 NDJSON 兼容

OpenAI 兼容接口返回 `data: {...}` 形式的 SSE，
但 Ollama 的 `/api/chat`、`/api/generate` 直接返回一行一个 JSON 的 NDJSON。
Ollama 每行都是一个裸 JSON 对象，所以凡是**以 `{` 起头的行**都走另一条解析路径：

```java
/**
 * 解析 Ollama 原生接口一行 NDJSON（/api/chat 的 message.content、
 * /api/generate 的 response），返回 [回答, 思考过程]；不是这类数据返回 null。
 */
private String[] parseNativeChunk(String line) {
    try {
        JSONObject obj = new JSONObject(line);
        String content = null;
        String thinking = null;
        JSONObject message = obj.optJSONObject("message");
        if (message != null) {
            content = message.optString("content", null);
            thinking = message.optString("thinking", null);
        }
        if (content == null) {
            content = obj.optString("response", null); // /api/generate
        }
        if (thinking == null) {
            thinking = obj.optString("thinking", null);
        }
        if (content == null && thinking == null) {
            return null;
        }
        return new String[]{
                content == null ? "" : content,
                thinking == null ? "" : thinking};
    } catch (Exception e) {
        return null;
    }
}

/** Ollama 原生接口用 done=true 标记结束。 */
private boolean isNativeDone(String line) {
    try {
        return new JSONObject(line).optBoolean("done", false);
    } catch (Exception e) {
        return false;
    }
}
```

两条分支共用同一套下游逻辑（字数统计、`notifyToken`、日志登记），
所以 SSE 和 NDJSON 的体感完全一致。

### 4.6 结束判定与错误分支

流读完之后，按这个顺序判定：

```java
if (code < 200 || code >= 300) {
    // 直接把服务端的 message 带出来，例如「model does not exist」
    String serverMessage = extractServerMessage(raw.toString());
    notifyError(callback, "HTTP " + code
            + (serverMessage == null ? "" : " · " + serverMessage), raw.toString());
} else if (answer.length() == 0 && reasoning.length() == 0
        && toolBuilders.size() == 0) {
    notifyError(callback, done ? "响应内容为空" : "流意外中断", raw.toString());
} else {
    // 正常：处理工具调用或结束
}
```

| 分支 | 条件 | 行为 |
|---|---|---|
| HTTP 失败 | `code < 200 || >= 300` | 附带服务端 `message`，用户直接看到原因 |
| 空响应 | 回答、思考、工具均为空 | 用于区分「正常收到 `[DONE]` 且内容为空」与「流意外中断」 |
| 工具调用 | 有 `tool_calls` | 见[第 5 章](#5-function-calling多轮工具调用) |
| 正常结束 | 其余 | `finish_reason` 检查 + `notifyComplete` |

**HTTP 错误也要读 `getErrorStream()`**，服务端返回的 JSON 错误体就在这里，
用户只能看到一个干巴巴的 `HTTP 404`。

```java
InputStream stream = code >= 200 && code < 300
        ? conn.getInputStream() : conn.getErrorStream();
```

`extractServerMessage` 同时兼容两种错误结构——顶层 `message`，或嵌套在 `error` 对象里：

```java
try {
    JSONObject obj = new JSONObject(text);
    String message = obj.optString("message", null);
    if (message == null) {
        JSONObject error = obj.optJSONObject("error");
        if (error != null) {
            message = error.optString("message", null);
        }
    }
    return message;
} catch (Exception ignored) {
    return null;
}
```

#### `finish_reason: length` 的专项提示

```java
if ("length".equals(finishReason)) {
    // 常见原因：Ollama 的 num_predict 默认只有 1024，
    // 思考过程先把预算吃光，正式回答一个字都没出来
    Log.w(LOG_TAG, "输出被截断：服务端返回 finish_reason=length。"
            + "可在接口设置里把「最大输出 Token」填大（如 8192），"
            + "或关掉模型思考");
}
```

这是一个非常典型的"服务端限制被误认为客户端 bug"的例子，详见 [10.1](#101-ollama-的-1024-天花板)。

### 4.7 原始响应与 usage 提取

每条 AI 消息都保留完整原始响应，长按气泡可以查看。

`usage` 的提取有个小技巧：流式响应里 usage 通常只在**最后一条事件**出现
（部分服务端还需要 `stream_options.include_usage`），所以从后往前找第一个即可：

```java
/**
 * 从原始响应里提取最后一个 usage 对象，供日志汇总展示。
 * @return usage 的 JSON 文本；没有则返回 null，不占日志
 */
private String extractUsage(String rawBody) {
    if (rawBody == null || rawBody.isEmpty()) {
        return null;
    }
    String[] lines = rawBody.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
        String payload = lines[i].trim();
        if (payload.startsWith("data:")) {
            payload = payload.substring(5).trim();
        }
        if (!payload.startsWith("{") || payload.indexOf("usage") < 0) {
            continue;
        }
        try {
            JSONObject usage = new JSONObject(payload).optJSONObject("usage");
            if (usage != null && usage.length() > 0) {
                return usage.toString();
            }
        } catch (Exception ignored) {
            // 这一行不是合法 JSON，继续往前找
        }
    }
    return null;
}
```

先用 `indexOf("usage")` 做一次廉价的字符串过滤，再尝试解析 JSON——
把几百行数据的解析次数降到常数级。

日志里的密钥也做了脱敏：

```java
/** 只显示 key 首尾，避免完整密钥进日志。 */
private String maskKey(String key) {
    if (key == null || key.length() <= 12) {
        return "***";
    }
    return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
}
```

---

## 5. Function Calling：多轮工具调用

这部分是「聊天机器人」和「Agent」的分界线：模型既能说话，也能要求客户端替它执行动作，
拿到结果后继续思考，直到给出最终答案。

### 5.1 工具声明

工具的 JSON Schema 声明集中在 `ToolRegistry`：

```java
public static final String GET_CURRENT_TIME = "get_current_time";
private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss EEEE";
private static final String TIMESTAMP = "timestamp";

/** 请求体里的 tools[] 声明。 */
public static JSONArray toolsJson() {
    JSONArray tools = new JSONArray();
    try {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONObject format = new JSONObject();
        format.put("type", "string");
        format.put("description", "时间格式，Java SimpleDateFormat 语法；"
                + "默认 yyyy-MM-dd HH:mm:ss EEEE，填 timestamp 返回毫秒时间戳");
        properties.put("format", format);
        parameters.put("properties", properties);
        parameters.put("required", new JSONArray());

        JSONObject function = new JSONObject();
        function.put("name", GET_CURRENT_TIME);
        function.put("description", "获取当前的日期和时间。"
                + "当用户问「现在几点」「今天几号」「今天星期几」等与时间相关的问题时调用。");
        function.put("parameters", parameters);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        tools.put(tool);
    } catch (Exception ignored) {
        // JSON key 均非空，不会发生
    }
    return tools;
}
```

执行侧**始终返回结果**——工具返回值是要喂回模型的，异常在这里被转成错误文本：

```java
/** 执行工具，返回给模型的结果文本（永不抛异常）。 */
public static String execute(String name, String argumentsJson) {
    if (!GET_CURRENT_TIME.equals(name)) {
        return "不支持的工具：" + name;
    }
    String format = DEFAULT_FORMAT;
    try {
        if (argumentsJson != null && !argumentsJson.trim().isEmpty()) {
            JSONObject args = new JSONObject(argumentsJson.trim());
            String requested = args.optString("format", null);
            if (requested != null && !requested.trim().isEmpty()
                    && !"null".equals(requested.trim())) {
                format = requested.trim();
            }
        }
    } catch (Exception ignored) {
        // 参数不合法就用默认格式
    }
    try {
        if (TIMESTAMP.equalsIgnoreCase(format)) {
            return String.valueOf(System.currentTimeMillis());
        }
        return new SimpleDateFormat(format, Locale.CHINA).format(new Date());
    } catch (Exception e) {
        return new SimpleDateFormat(DEFAULT_FORMAT, Locale.CHINA).format(new Date());
    }
}
```

**注意 `required` 是空数组**——`format` 是可选参数，由模型自行决定是否填。
如果 `arguments` 解析失败（模型常常吐出畸形 JSON），就退回默认格式继续走流程。

> **为什么只做只读工具？**
> 这是有意为之。小模型（尤其是本地跑的那些）判断力有限，
> 有副作用的工具（发消息、删文件、调接口）需要配套确认与审计，已列入路线图。
> 当前阶段只保留只读能力，是安全性上的取舍。

### 5.2 流式分片合并

`tool_calls` 是**流式分片**下发的：`id`、`name`、`arguments` 都是一片一片拼出来的，
可能跨越好几条 SSE 事件。所以需要一个按 `index` 分组的累积器：

```java
/**
 * tool_calls 分片累积器。
 */
private static class ToolCallBuilder {
    String id;
    final StringBuilder name = new StringBuilder();
    final StringBuilder arguments = new StringBuilder();
}
```

```java
/**
 * 把一条已解析事件的 tool_calls 分片并进累积器。
 *
 * <p>delta.tool_calls 是数组，每个元素带 index，id / name / arguments
 * 都是流式分片拼出来的，所以这里只做追加，不做覆盖。
 */
private void mergeToolCallDeltas(ParsedEvent event, SparseArray<ToolCallBuilder> builders) {
    if (event == null || event.toolCallDeltas == 0) {
        return;
    }
    for (int i = 0; i < event.toolCallDeltas; i++) {
        JSONObject call = event.toolCallArray.optJSONObject(i);
        if (call == null) {
            continue;
        }
        int index = call.optInt("index", i);
        ToolCallBuilder builder = builders.get(index);
        if (builder == null) {
            builder = new ToolCallBuilder();
            builders.put(index, builder);
        }
        String id = call.optString("id", null);
        if (id != null && !id.isEmpty() && !"null".equals(id)) {
            builder.id = id;
        }
        JSONObject function = call.optJSONObject("function");
        if (function == null) {
            continue;
        }
        String name = function.optString("name", null);
        if (name != null && !name.isEmpty() && !"null".equals(name)) {
            builder.name.append(name);
        }
        String arguments = function.optString("arguments", null);
        if (arguments != null && !arguments.isEmpty() && !"null".equals(arguments)) {
            builder.arguments.append(arguments);
        }
    }
}
```

两个要点：

1. **只追加**。每个分片都作为增量接到尾部，覆盖会破坏已累积的内容。
2. **用 `SparseArray<ToolCallBuilder>` 承载分片**。
   `index` 是小整数，SparseArray 在 Android 上省掉了自动装箱。

收尾时整理成列表：

```java
private List<ToolCall> buildToolCalls(SparseArray<ToolCallBuilder> builders) {
    List<ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < builders.size(); i++) {
        ToolCallBuilder builder = builders.valueAt(i);
        if (builder == null || builder.name.length() == 0) {
            continue;
        }
        ToolCall call = new ToolCall();
        String id = builder.id;
        call.setId(id == null || id.isEmpty() ? "call_" + i : id);
        call.setName(builder.name.toString());
        call.setArguments(builder.arguments.toString());
        calls.add(call);
    }
    return calls;
}
```

如果模型省略了 `id`（部分服务端确实如此），这里补一个 `call_<序号>`——
因为下一步构造 `role=tool` 消息时必须要 `tool_call_id` 才能对上。

### 5.3 多轮循环

核心是一段**递归**：

```java
// 模型发起了工具调用：本地执行后把结果回传，再自动发起下一轮
List<ToolCall> roundCalls = buildToolCalls(toolBuilders);
if (!roundCalls.isEmpty() && toolExecutor != null && roundsLeft <= 0) {
    // 轮次用尽仍要调工具：不再循环，明确提示，避免返回一条空消息
    Log.w(LOG_TAG, "工具调用轮次已达上限（" + MAX_TOOL_ROUNDS + "轮），停止调用");
    notifyError(callback, "工具调用轮次已达上限（最多 "
            + MAX_TOOL_ROUNDS + " 轮），已停止", raw.toString());
} else if (!roundCalls.isEmpty() && toolExecutor != null) {
    for (ToolCall call : roundCalls) {
        call.setResult(toolExecutor.execute(call.getName(), call.getArguments()));
        Log.d(LOG_TAG, "工具调用：" + call.getName()
                + "，参数：" + call.getArguments()
                + "，结果：" + call.getResult());
    }
    List<ToolCall> allCalls = new ArrayList<>(carriedToolCalls);
    allCalls.addAll(roundCalls);
    notifyToolCalls(callback, allCalls);

    // ① assistant 的空消息 + tool_calls
    List<ChatMessage> nextHistory = new ArrayList<>(history);
    ChatMessage assistantCall = new ChatMessage(ChatMessage.ROLE_AI, "");
    assistantCall.setToolCalls(roundCalls);
    nextHistory.add(assistantCall);
    // ② 每个调用对应一条 role=tool 结果
    for (ToolCall call : roundCalls) {
        ChatMessage toolMessage =
                new ChatMessage(ChatMessage.ROLE_TOOL, call.getResult());
        toolMessage.setToolCallId(call.getId());
        nextHistory.add(toolMessage);
    }
    // ③ 递归下一轮
    doRequest(nextHistory, callback, roundsLeft - 1, allCalls);
    return;
}
```

消息结构必须严格符合 OpenAI 的约定：

```
... 之前的对话 ...
assistant  { content: "", tool_calls: [ {id, function:{name, arguments}} ] }
tool       { tool_call_id: "<对应上面的 id>", content: "<执行结果>" }
assistant  { content: "最终回答" }
```

几个容易出错的地方：

- **`assistant` 那条消息的 `content` 是空的**，工具调用信息全在 `tool_calls` 里。
  序列化时如果 content 为空则省略该字段（见 `ContextMessage.toJson()`）。
- **每条 `role=tool` 必须带 `tool_call_id`**，且要和前面 `tool_calls` 里的 `id` 对得上。
- **`carriedToolCalls` 跨轮累积**：最终 `onToolCall` 回调给用户的是所有轮次的调用总和，
  界面上一次展示完整的调用轨迹，覆盖每一轮。

### 5.4 三道闸门与诊断日志

tools 的下发要过三道闸门：

```java
boolean switchOn = config.isFunctionCallingEnabled();          // 设置页开关，默认 false
boolean toolsEnabled = switchOn && toolExecutor != null && roundsLeft > 0;
// 排查用：请求体里没看到 tools 时，看这行就知道是被哪个条件挡住的
Log.d(LOG_TAG, "工具调用状态：开关=" + switchOn
        + "，执行器已注入=" + (toolExecutor != null)
        + "，剩余轮次=" + roundsLeft
        + "，本次下发 tools=" + toolsEnabled);
```

| 闸门 | 含义 | 为什么需要 |
|---|---|---|
| `isFunctionCallingEnabled()` | 设置页开关，默认关 | 只给支持工具调用的模型开启，请求更轻 |
| `toolExecutor != null` | 是否注入了执行器 | 生成标题这类轻量请求走独立引擎 |
| `roundsLeft > 0` | 还剩轮次 | 最后一轮单独收口，循环次数有上界 |

这行诊断日志是踩坑的产物——确认 tools 是否真的进了请求体时，
有了它能一眼看出是哪个条件挡住的。

注入时机（`MainActivity.onCreate`）：

```java
openAiEngine = new OpenAiEngine(apiConfig);
// 主对话允许使用本地工具；生成标题的请求不带工具，避免小模型乱调用
openAiEngine.setToolExecutor(new ToolExecutor() {
    @Override
    public String execute(String name, String argumentsJson) {
        return ToolRegistry.execute(name, argumentsJson);
    }
});
titleEngine = new OpenAiEngine(apiConfig);   // 不注入执行器
```

`ApiSettingsActivity` 里做连通性测试的引擎也注入了执行器，
所以「测试连接」能完整跑通一轮工具调用。

### 5.5 轮次上限与降级

`MAX_TOOL_ROUNDS = 3`，用尽之后**明确报错**：

```java
notifyError(callback, "工具调用轮次已达上限（最多 "
        + MAX_TOOL_ROUNDS + " 轮），已停止", raw.toString());
```

返回空消息的处理效果最差——界面上只剩一片空白。
给一条明确的提示，用户至少知道是轮次用完了，可以换个问法或调大上限。

另外，`roundsLeft > 0` 这个闸门意味着**最后一轮的请求只带对话**，
所以模型在最后一轮握有的只有对话，循环必然终止。
这是双保险：既有轮次上限的软限制，也有剥离工具的硬限制。

---

## 6. 上下文工程：预算分层与滚动压缩

这是本项目最有技术含量的一块，也是从「能跑」到「能长时间稳定跑」的关键。
全部代码在 `app/src/main/java/com/doudou/x/ai/context/`，8 个文件，逻辑完全自包含。

### 6.1 动机

会话状态由客户端持有，每次请求都要把完整历史回传。对话一长就会遇到三个问题：

| 问题 | 表现 |
|---|---|
| 超窗 | 历史超过模型上下文窗口，服务端直接报错 |
| 成本 | 每轮重复发送几千 token 的旧对话，钱和延迟都翻倍 |
| 降质 | 关键信息被淹没在大量低相关历史里，模型注意力被稀释 |

朴素的解决办法是"截断前面的历史"，但这样会丢掉早期确认过的事实和用户目标，
对话越长越明显——模型会逐渐"失忆"。

本项目采用的是：**分层预算裁剪 + 滚动摘要压缩**。
近期对话原样保留，更早的对话压成结构化摘要，摘要只往前滚动。

### 6.2 分层预算模型

```java
public final class ContextBudget {

    /** 默认输入窗口预算，按 8K 估；服务端窗口更大时可整体调大。 */
    public static final int DEFAULT_TOTAL = 8192;

    private final int totalTokens;
    /** 给模型输出预留的部分，参与总量扣减，避免输入吃满窗口。 */
    private final int outputReserve;
    private final int systemReserve;
    private final int toolReserve;
    private final int summaryReserve;
    /** 最近多少轮（一轮 = 一条 user 及其后续）原样保留，不参与裁剪。 */
    private final int keepRecentTurns;
    /** 压缩时保留最近多少轮不被压进摘要。 */
    private final int compactKeepTurns;

    /** 默认预算：8K 窗口，留 1K 给输出，最近 3 轮硬保留，压缩时留 2 轮。 */
    public static ContextBudget defaults() {
        return new ContextBudget(DEFAULT_TOTAL, 1024, 512, 512, 600, 3, 2);
    }

    /** 按窗口大小生成一套预算，各层配额按默认比例缩放。 */
    public static ContextBudget forWindow(int windowTokens) {
        int total = Math.max(2048, windowTokens);
        return new ContextBudget(total, total / 8, total / 16,
                total / 16, total / 12, 3, 2);
    }

    /** 扣掉系统提示词、工具声明与摘要后，留给对话历史的预算。 */
    public int historyBudget(int systemTokens, int toolTokens, int summaryTokens) {
        int used = Math.min(systemTokens, systemReserve)
                + Math.min(toolTokens, toolReserve)
                + Math.min(summaryTokens, summaryReserve);
        int left = totalTokens - outputReserve - used;
        // 再紧也要给最近一轮留位置，否则模型看不到当前提问
        return Math.max(left, 256);
    }
}
```

**裁剪优先级（从先砍到后砍）**：

```
更老的历史  →  摘要  →  最近 N 轮（受保护）
                              ↑
        系统提示词、工具声明：只做上限保护，不参与裁剪
```

前两层固定保留，是因为改动它们会直接改变模型行为——
系统提示词定人设，工具声明定能力，二者的地位与对话长度脱钩。

`historyBudget` 用 `Math.min(x, reserve)` 夹紧：某一层超出配额时，
按配额计入，历史预算因此恒为正——系统提示词再长也压不到零以下。
最后 `Math.max(left, 256)` 保证再紧也有 256 token 给最近一轮。

### 6.3 token 估算

端侧只有启发式估算可用——**估算偏高只是少发一点历史，换来的是超窗风险的归零**。

```java
/** 单条消息的固定开销：role、content 键名与分隔符。 */
public static final int PER_MESSAGE = 4;
/** 工具结果消息多一个 tool_call_id。 */
public static final int TOOL_MESSAGE = 8;
/** 一次工具调用声明的壳开销：id + type + function。 */
public static final int PER_TOOL_CALL = 12;

/** 估算一段文本的 token 数；null 与空串返回 0。 */
public static int estimate(String text) {
    if (text == null || text.isEmpty()) {
        return 0;
    }
    int cjk = 0;
    int other = 0;
    for (int i = 0; i < text.length(); i++) {
        if (isCjk(text.charAt(i))) {
            cjk++;
        } else {
            other++;
        }
    }
    return cjk + (other + 3) / 4;
}

/** CJK 汉字、假名、谚文、全角与中文标点都按 1 字符 1 token 计。 */
private static boolean isCjk(char c) {
    return (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0x3400 && c <= 0x4DBF)
            || (c >= 0x3040 && c <= 0x30FF)
            || (c >= 0xAC00 && c <= 0xD7AF)
            || (c >= 0xFF00 && c <= 0xFF60)
            || (c >= 0x3000 && c <= 0x303F);
}
```

为什么中文按 1 字 1 token：主流 tokenizer 对中文的分词粒度大约就是 1～1.5 token/字，
按 1 算是偏保守的。英文按 4 字符 1 token 是业界常用经验值。

每条消息还要加结构开销——JSON 的 `role`、`content` 键名和分隔符本身也占 token，
工具调用还要算上 `id`、`type`、`function` 壳和参数。

### 6.4 按组裁剪算法

**这是裁剪里最容易踩的坑**：如果只留下 `assistant` 的 `tool_calls` 而丢掉了对应的
`role=tool` 结果，多数服务端会直接返回 400。所以裁剪以「组」为单位整体进行。

```java
/**
 * 把历史切成「组」：一条 assistant 的工具调用连同它后续的所有工具结果算一组，
 * 其余每条自成一组。工具结果挂在调用所在的组里，裁剪时不会分离。
 */
private static List<List<ContextMessage>> group(List<ChatMessage> history) {
    List<List<ContextMessage>> groups = new ArrayList<>();
    if (history == null) {
        return groups;
    }
    for (ChatMessage msg : history) {
        ContextMessage item = ContextMessage.fromChat(msg);
        if (item == null) {
            continue;
        }
        if (msg.getRole() == ChatMessage.ROLE_TOOL && !groups.isEmpty()) {
            groups.get(groups.size() - 1).add(item);
            continue;
        }
        List<ContextMessage> group = new ArrayList<>();
        group.add(item);
        groups.add(group);
    }
    return groups;
}
```

主算法（从最新往回装）：

```java
List<List<ContextMessage>> groups = group(history);
int protectFrom = protectFrom(groups, budget.getKeepRecentTurns());
int available = budget.historyBudget(
        stats.systemTokens, stats.toolTokens, stats.summaryTokens);

// 从最新往回装：装不下就停，剩下更老的整组丢弃
int used = 0;
int firstKept = groups.size();
for (int i = groups.size() - 1; i >= 0; i--) {
    int groupTokens = groupTokens(groups.get(i));
    if (i >= protectFrom) {
        used += groupTokens; // 受保护窗口：超预算也要带上
        firstKept = i;
        continue;
    }
    if (used + groupTokens > available) {
        break;
    }
    used += groupTokens;
    firstKept = i;
}
```

**为什么从后往前**：对话的重要性基本随时间递减，最新的内容最有价值。
从后往前装，溢出部分的自然是最老的，直接整组淘汰，逻辑最简。

保护窗口的起点：

```java
/** 受保护窗口的起始组下标：最后 keepTurns 条 user 消息所在的组都要留下。 */
private static int protectFrom(List<List<ContextMessage>> groups, int keepTurns) {
    int found = 0;
    int from = groups.size();
    for (int i = groups.size() - 1; i >= 0; i--) {
        for (ContextMessage item : groups.get(i)) {
            if (ContextMessage.ROLE_USER.equals(item.getRole())) {
                from = i;
                found++;
                break;
            }
        }
        if (found >= keepTurns) {
            break;
        }
    }
    return from;
}
```

以 **user 消息**为轮的边界，
这样"一轮"就是语义完整的"一问一答"，保护窗口里始终是一段完整对话。

### 6.5 保护窗口与截断

受保护的最近几轮自身也可能超预算（比如用户贴了一大段代码）。
这时保留整轮、只截断内容——当前提问必须留在窗口里：

```java
// 受保护窗口自身就可能超预算：从最早的一条开始截断，直到装得下
int overflow = used - available;
for (int i = firstKept; i < groups.size() && overflow > 0; i++) {
    List<ContextMessage> group = groups.get(i);
    for (int j = 0; j < group.size() && overflow > 0; j++) {
        ContextMessage item = group.get(j);
        if (item.getKind() != ContextMessage.KIND_HISTORY) {
            continue;
        }
        int before = item.getTokens();
        ContextMessage cut = item.truncateTo(before - overflow);
        group.set(j, cut);
        overflow -= (before - cut.getTokens());
        stats.truncatedCount++;
    }
}
stats.overflow = overflow > 0;
```

截断实现：

```java
/**
 * 按剩余预算截断内容，保留开头部分。
 *
 * <p>中英混排下「字符数 / token」不是常数，所以先按比例缩一次，
 * 再逐步回退直到确实装得下。
 */
public ContextMessage truncateTo(int budgetTokens) {
    if (budgetTokens <= 0 || tokens <= budgetTokens) {
        return this;
    }
    int length = (int) (content.length() * ((double) budgetTokens / tokens));
    for (int i = 0; i < 4; i++) {
        int end = Math.max(0, Math.min(length, content.length()));
        String cut = content.substring(0, end) + TRUNCATE_MARK;
        if (TokenEstimator.estimate(cut) <= budgetTokens) {
            return new ContextMessage(role, cut, kind, source, true);
        }
        length = length * 9 / 10;
    }
    return new ContextMessage(role, TRUNCATE_MARK, kind, source, true);
}
```

因为字符数和 token 数在中英混排下是非线性的，先按比例缩一次，
再用 `estimate` 验证，超出就打九折重试，最多四次。保留了开头，并追加 `…（已截断）` 标记，
让模型知道这段被截过。

### 6.6 滚动压缩

裁剪解决"装得下"，压缩解决"记得住"。

```java
/**
 * 滚动压缩：把更早的对话增量压成一份结构化摘要，供后续轮次使用。
 *
 * <p>三个要点：
 * <ul>
 *   <li>追加式：旧摘要连同新增内容一起交给模型，产出的新摘要整体覆盖旧的，
 *       摘要只会往前滚，不会回头重写已经压过的部分；</li>
 *   <li>只压增量：记住已压到第几条，下次从那里继续，同一段对话不会被压第二次；</li>
 *   <li>不阻塞：压缩在后台跑，结果下一次请求才生效，失败就静默跳过，最坏退化成裁剪。</li>
 * </ul>
 */
public final class RollingCompressor {

    /** 增量不足这个条数就不值得压一次。 */
    public static final int MIN_NEW_MESSAGES = 4;
    /** 单条消息参与摘要时最多取的字符数。 */
    private static final int PER_MESSAGE_CHARS = 400;
```

**是否值得压一次**：

```java
public boolean shouldCompact(List<ChatMessage> history, SummaryStore.Summary summary,
                             ContextBudget budget) {
    if (history == null || engine == null || summary == null) {
        return false;
    }
    int end = compactEnd(history, budget);
    return end - summary.compressedCount >= MIN_NEW_MESSAGES;
}
```

**压缩区间的结束位置**——最近 `compactKeepTurns` 条 user 消息之前：

```java
/** 压缩区间的结束下标（不含）：最近 compactKeepTurns 条 user 消息之前的部分。 */
private static int compactEnd(List<ChatMessage> history, ContextBudget budget) {
    int keep = budget.getCompactKeepTurns();
    int found = 0;
    for (int i = history.size() - 1; i >= 0; i--) {
        if (history.get(i).getRole() == ChatMessage.ROLE_USER) {
            found++;
            if (found >= keep) {
                return i;
            }
        }
    }
    return 0;
}
```

为什么要留最近几轮：这几轮本来就会原样发给模型，压进摘要反而重复。

**执行压缩**：

```java
public void compactAsync(final List<ChatMessage> history, final String sessionKey,
                         final SummaryStore.Summary summary, final ContextBudget budget,
                         final Runnable onDone) {
    final int end = compactEnd(history, budget);
    String chunk = render(history, summary.compressedCount, end);
    String prompt = String.format(SUMMARY_PROMPT,
            summary.hasSummary() ? summary.text : "（无）", chunk);
    List<ChatMessage> request = new ArrayList<>();
    request.add(new ChatMessage(ChatMessage.ROLE_USER, prompt));
    Log.d(LOG_TAG, "上下文压缩：开始压缩第 " + summary.compressedCount + " 到 " + end + " 条");
    engine.streamReply(request, new AiEngine.StreamCallback() {
        // ...
        @Override
        public void onComplete(String fullText, String thinkingText, String rawResponse) {
            String text = clean(fullText);
            if (text.isEmpty()) {
                Log.w(LOG_TAG, "上下文压缩：模型返回空摘要，保留原摘要");
            } else {
                store.save(sessionKey, text, end);
                Log.d(LOG_TAG, "上下文压缩：完成，已压到第 " + end + " 条，摘要 "
                        + text.length() + " 字");
            }
            onDone.run();
        }

        @Override
        public void onError(String errorMessage, String rawResponse) {
            // 压缩失败不影响对话本身，下一次超预算时会再试
            Log.w(LOG_TAG, "上下文压缩：失败（" + errorMessage + "），本轮继续按裁剪发送");
            onDone.run();
        }
    });
}
```

参与摘要的内容渲染成纯文本，**只取正式回答**（思考过程篇幅占比高、后续复用价值低）：

```java
/** 把 [from, to) 渲染成纯文本。 */
private static String render(List<ChatMessage> history, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = Math.max(0, from); i < to && i < history.size(); i++) {
        ChatMessage msg = history.get(i);
        String content = msg.getContent();
        if (content == null || content.isEmpty() || msg.isError()) {
            continue;
        }
        sb.append(msg.getRole() == ChatMessage.ROLE_USER ? "用户：" : "助手：");
        sb.append(content.length() > PER_MESSAGE_CHARS
                ? content.substring(0, PER_MESSAGE_CHARS) + "…" : content);
        sb.append('\n');
    }
    return sb.toString();
}
```

### 6.7 摘要提示词设计

```java
/**
 * 摘要提示词。固定要求输出四段：用户目标、已确认事实、失败过的方案、待办。
 * 这四项正是长对话里最容易丢、丢了又最影响后续判断的信息。
 */
private static final String SUMMARY_PROMPT =
        "请把下面这段较早的对话压缩成一份紧凑摘要，供后续对话继续参考。\n"
                + "\n【已有摘要】\n%s\n"
                + "\n【新增对话】\n%s\n"
                + "\n只输出摘要正文，不要解释、不要寒暄。必须包含这四部分：\n"
                + "1. 用户目标：\n"
                + "2. 已确认事实：\n"
                + "3. 已尝试但失败的方案：\n"
                + "4. 待办与未完成事项：\n"
                + "总长度不超过 400 字，没有的部分写「无」。";
```

**为什么摘要要按这四段组织**：

| 段落 | 作用 |
|---|---|
| 用户目标 | 长对话里最容易走偏的就是忘了最初要干什么 |
| 已确认事实 | 用户说过的偏好、约束、背景，丢了就要重新问一遍 |
| **已尝试但失败的方案** | 价值最高的一段——有了它，模型才会绕开验证失败的做法 |
| 待办事项 | 多步任务中断后的续跑依据 |

"已尝试但失败的方案"这一条是 Agent 实践里价值最高的：
有了它，模型就能绕开已经走过的坑，用户看到的是"它在推进"。

### 6.8 延迟一轮生效的取舍

这是本模块最重要的一个工程决策。

**压缩需要一次模型调用，而模型调用是异步的。**
如果让 `buildMessages` 等压缩完成，整个请求链路都要改成异步，引擎的同步结构会被破坏。

所以改成：

```
第 N 次请求：  按当前摘要裁剪 → 发送 → 后台异步压缩 → 结果写库
第 N+1 次请求：读到新摘要 → 按新摘要裁剪 → 发送 → ...
```

**代价**：压缩结果延迟一轮生效。
**收益**：引擎的异步流程维持现状，压缩失败也与对话链路隔离。

这个取舍在这个场景里是划算的——压缩属于"优化"，
晚一轮用上最多多花一点 token，功能链路保持可用。

门面里的实现：

```java
/**
 * 上下文模块门面：对引擎只暴露 buildMessages 一个入口。
 *
 * <p>分工：
 * <ul>
 *   <li>每次请求同步做一次分层裁剪，保证不超预算；</li>
 *   <li>发现历史偏长就后台触发一次压缩，结果写库，下一次请求才用上；</li>
 *   <li>压缩失败不影响本轮，最坏情况退化成裁剪。</li>
 * </ul>
 */
public JSONArray buildMessages(List<ChatMessage> history, String systemPrompt,
                               boolean withTools) {
    String sessionKey = sessionKey(history);
    SummaryStore.Summary summary =
            store == null ? SummaryStore.Summary.empty() : store.load(sessionKey);
    JSONArray tools = withTools ? ToolRegistry.toolsJson() : null;
    ContextPlan plan = new ContextAssembler(budget).assemble(
            history,
            systemPrompt,
            tools == null ? null : tools.toString(),
            tools == null ? 0 : tools.length(),
            summary.text);
    Log.d(LOG_TAG, "上下文组装：合计≈" + plan.stats.totalTokens() + " token，系统="
            + plan.stats.systemTokens + "，工具=" + plan.stats.toolTokens
            + "，摘要=" + plan.stats.summaryTokens + "，历史=" + plan.stats.historyTokens
            + "，丢弃=" + plan.stats.droppedCount + "，截断=" + plan.stats.truncatedCount
            + (plan.stats.overflow ? "，保护窗口仍超预算" : ""));
    maybeCompact(history, sessionKey, summary);
    return plan.toJsonArray();
}
```

去重与前置条件：

```java
private void maybeCompact(final List<ChatMessage> history, final String sessionKey,
                          final SummaryStore.Summary summary) {
    if (!initialized || compressor == null || config == null || !config.isReady()) {
        return; // 未配置真实接口（走本地模拟）时不压缩
    }
    if (!compressor.shouldCompact(history, summary, budget)) {
        return;
    }
    synchronized (compacting) {
        if (!compacting.add(sessionKey)) {
            return; // 该会话已在压缩中
        }
    }
    compressor.compactAsync(history, sessionKey, summary, budget, new Runnable() {
        @Override
        public void run() {
            synchronized (compacting) {
                compacting.remove(sessionKey);
            }
        }
    });
}
```

三重门槛：真实接口配置就绪才压缩、增量达到条数才压缩、同一会话并发只压一次。

### 6.9 会话 key 与持久化

```java
/**
 * 会话标识：首条消息的时间戳 + 内容长度。
 * 同一会话里首条消息恒定，跨轮、跨启动都能命中同一份摘要。
 */
private static String sessionKey(List<ChatMessage> history) {
    if (history == null || history.isEmpty()) {
        return "empty";
    }
    ChatMessage first = history.get(0);
    String content = first.getContent();
    return first.getTimestamp() + "_" + (content == null ? 0 : content.length());
}
```

为什么要这么定 key：引擎手上的入参是 `List<ChatMessage>`，这一层能拿到的稳定标识只有首条消息。
而同一个会话的第一条消息（用户发送的第一句话）在整轮对话里是恒定的，
用它做标识既能跨轮命中，也能在 App 重启后命中同一份摘要。

存储结构：

```java
/** 一条会话的压缩进度。 */
public static final class Summary {
    /** 摘要正文，没有时为空串。 */
    public final String text;
    /** 已被压进摘要的历史条数，下一次从这里继续。 */
    public final int compressedCount;
}
```

`compressedCount` 是滚动压缩能省 token 的关键——
有了它，每次只压新增的那一段，长对话下的开销保持线性。

存储还有两个保护：

```java
/** 最多保留的会话数，超出丢最旧的，避免随时间无限增长。 */
private static final int MAX_ENTRIES = 50;
```

超出上限时按更新时间排序，丢掉最旧的一批（`trimIfNeeded`）。

### 6.10 完整调用链

```
OpenAiEngine.buildRequestBody(history, withTools)
  │
  ├─ 选择 toSend（是否发完整历史）
  ├─ 取 systemPrompt
  │
  └─ ContextManager.buildMessages(toSend, systemPrompt, withTools)
        │
        ├─ sessionKey(history)              → 会话标识
        ├─ SummaryStore.load(key)           → 已有摘要 + 压缩进度
        ├─ ToolRegistry.toolsJson()         → 工具声明（用于算预算）
        │
        ├─ ContextAssembler.assemble(...)
        │     ├─ 过滤：error 消息、空内容占位消息
        │     ├─ 建组：tool_calls + 其 tool 结果 同组
        │     ├─ 算预算：historyBudget(system, tools, summary)
        │     ├─ 从后往前装：超预算则整组丢弃
        │     ├─ 保护窗口：最近 keepRecentTurns 轮必留
        │     ├─ 超限截断：从最早一条开始截
        │     └─ 输出顺序：system → summary → history
        │
        ├─ maybeCompact(...)                 → 后台异步压缩（延迟一轮生效）
        └─ plan.toJsonArray()                → 请求体的 messages
```

日志输出一行组装统计，排查上下文问题时直接看：

```
上下文组装：合计≈1536 token，系统=48，工具=36，摘要=112，历史=1340，丢弃=0，截断=0
```

---

## 7. 记忆系统：三层记忆

> 代码在 `app/src/main/java/com/doudou/x/ai/memory/`，6 个文件。
> 与上下文模块共用同一套节拍：召回同步、抽取异步且延迟一轮生效。

### 7.1 三层划分的依据

分层的依据是**这条记忆的有效期有多长**——
不同信息的保质期差好几个数量级，分开存才能设计各自合理的遗忘策略。

| 类型 | 存什么 | 半衰期 | 召回权重 |
|---|---|---|---|
| **情景** `EPISODIC` | 某次对话聊了什么、结论是什么 | 3 天 | 1.0 |
| **语义** `SEMANTIC` | 关于用户的稳定事实（身份、偏好、约束） | 30 天 | 1.2 |
| **程序** `PROCEDURAL` | 用户要求的做事方式与输出格式 | 365 天 | 1.5 |

```java
public enum MemoryType {

    /**
     * 情景记忆：某次对话聊了什么、结论是什么。
     * 只对后续相似话题有用，忘得最快。
     */
    EPISODIC("episodic", "情景", 3 * 24 * 3600_000L, 1.0f),

    /**
     * 语义记忆：关于用户的稳定事实（身份、偏好、约束）。
     * 变化慢，长期有效。
     */
    SEMANTIC("semantic", "语义", 30 * 24 * 3600_000L, 1.2f),

    /**
     * 程序记忆：用户要求的工作方式与输出格式。
     * 一旦形成几乎不会变，所以半衰期设得极长。
     */
    PROCEDURAL("procedural", "程序", 365 * 24 * 3600_000L, 1.5f);
}
```

**程序记忆是个特殊角色**：它描述"怎么做事"（先给结论、代码要可运行），
和当前提问的字面相关度很低，但影响的是整轮回答的风格。
所以它**按遗忘分直接取前几条固定带上**——见 [7.5](#75-检索字符-bigram-jaccard)。

### 7.2 记忆条目与遗忘评分

```java
public final class MemoryItem {
    private final String id;
    private final MemoryType type;
    /** 主语 / 主题，冲突消解就靠它。 */
    private final String key;
    private String content;
    private long accessedAt;
    private int hits;
    /** 抽取时的把握程度，参与遗忘排序。 */
    private float confidence;

    /**
     * 遗忘评分：命中越多、越近访问、越可信、类型越稳定，分越高。
     *
     * <p>时间衰减用指数形式：过了半衰期得分减半，
     * 而不是「超过 N 天直接删除」——后者会让记忆库出现断崖。
     */
    public float score(long now) {
        long age = Math.max(0L, now - accessedAt);
        double decay = Math.pow(0.5, age / (double) type.getHalfLifeMs());
        return (1f + hits * 0.5f) * (float) decay * confidence * type.getRecallWeight();
    }
}
```

三个输入共同决定一条记忆的存亡：

- **时间衰减**（指数）：用得越少、隔得越久，分数越低。指数衰减让淘汰平滑推进，
  记忆库因此保持连续，一批记忆的过期时间自然错开。
- **命中次数**：被召回过说明有用，`touch()` 会累加。
- **置信度**：模型抽取时的把握程度，推测性内容分数天然更低。

容量超限（200 条）时按这个分数从低到高淘汰。

### 7.3 冲突消解

这是记忆系统里最容易做错的一件事。

如果新事实直接追加，记忆库里会同时存在
「用户是 Android 工程师」和「用户是后端工程师」，
被一起召回时模型反而更困惑——两条互相矛盾的信息比零信息更干扰模型。

```java
/** 同一类型且 key 相同即视为同一条记忆的不同版本。 */
private static boolean isSame(MemoryItem a, MemoryItem b) {
    if (a == null || b == null || a.getType() != b.getType()) {
        return false;
    }
    String ka = a.getKey();
    String kb = b.getKey();
    if (ka.isEmpty() || kb.isEmpty()) {
        return false; // 没有 key 的条目无法判定同一性，只能新增
    }
    return ka.equals(kb);
}
```

命中同一条时**用覆盖完成更新**：

```java
/**
 * 用新内容覆盖（保留创建时间与命中数）。
 * 用于冲突消解：同一 key 出现矛盾信息时，以新的为准。
 */
public void overwrite(String newContent, float newConfidence) {
    this.content = newContent == null ? "" : newContent.trim();
    this.confidence = clamp(newConfidence);
    this.updatedAt = System.currentTimeMillis();
}
```

保留创建时间与命中数很重要——这条记忆的"资历"始终延续，内容更新时重新起算。

### 7.4 抽取：只记该记的

**只抽取该记的部分**是关键。每轮对话全量入库时，
检索质量会被大量寒暄稀释掉，几百条之后可用性急剧下降。

提示词里明确要求：只写对话里明确出现的信息、推测的一律略过、最多 5 条、
已有记忆里出现过的跳过、**本轮确有值得记的内容才输出条目**——
最后这一条要求很重要，模型因此在素材单薄时直接交白卷。

```
请从下面这段对话中提取值得长期记住的信息，供以后的对话参考。

【已记住的内容】（这些不要重复；如果新信息与之矛盾，以新的为准）
...

【本次对话】
...

只输出一个 JSON 数组，不要解释、不要用 markdown 代码块围栏。每条格式：
{"type":"semantic|procedural|episodic","key":"主题或主语","content":"一句话事实","confidence":0.9}

type 的取值：
· semantic：关于用户的稳定事实（身份、偏好、约束、明确说过的信息）
· procedural：用户要求的做事方式或输出格式（例如「先给结论」「代码要可运行」）
· episodic：本次对话的主题与最终结论，一句话概括
```

解析必须容错——模型经常在 JSON 外包一层 markdown 代码块，或在数组前后加废话：

```java
static List<MemoryItem> parse(String raw) {
    List<MemoryItem> items = new ArrayList<>();
    if (raw == null) {
        return items;
    }
    String text = raw.trim();
    if (text.startsWith("```")) {
        int firstNewline = text.indexOf('\n');
        if (firstNewline >= 0) {
            text = text.substring(firstNewline + 1);
        }
        int fence = text.lastIndexOf("```");
        if (fence >= 0) {
            text = text.substring(0, fence);
        }
    }
    int start = text.indexOf('[');
    int end = text.lastIndexOf(']');
    if (start < 0 || end <= start) {
        return items;
    }
    try {
        JSONArray arr = new JSONArray(text.substring(start, end + 1));
        // 逐条解析：单条坏掉只丢那一条
        ...
    } catch (Exception e) {
        // 整体解析失败就当这次没抽到，下次再试
    }
    return items;
}
```

抽取的触发条件与压缩一致：**增量 ≥ 6 条消息才抽一次**，
进度按会话记录（`progress_<sessionKey>`），同一会话并发只跑一次。

### 7.5 检索：字符 bigram Jaccard

端侧调 embedding 接口要多一次网络往返，还依赖服务端是否提供该能力。
这里用**字符 bigram 的 Jaccard 相似度**：中文表现好、零依赖、可解释——
召回错了能立刻看出是哪里匹配上的。

```java
/**
 * 相关度：字符 bigram 的 Jaccard 相似度。
 *
 * <p>用 bigram 而不是单词，是因为中文没有空格分词，
 * 而双字组合（"记忆"、"工程"）已经能提供足够的区分度。
 */
public static float similarity(String query, String text) {
    return similarity(bigrams(query), bigrams(text));
}
```

两个阈值设计：

```java
/** 程序记忆固定带上的条数。 */
private static final int PROCEDURAL_ALWAYS = 2;
/** 相关度低于这个阈值就不召回，宁缺毋滥——无关记忆比没记忆更干扰模型。 */
private static final float MIN_RELEVANCE = 0.12f;
```

- **程序记忆按遗忘分直接取前 2 条固定带上**，全程参与下发。
- 语义/情景记忆的相关度需达到 0.12 才召回——**低相关记忆比零记忆更干扰模型**。

接口保持稳定，后续要换成 embedding 只需替换 `similarity` 一个方法。

### 7.6 注入上下文的位置

记忆作为**独立的一层预算**，下发成一条独立的 system 消息：

```
system prompt        ← 人设
长期记忆              ← 关于用户的持久事实（全局背景）
本次会话摘要          ← 本次聊了什么
最近对话              ← 最近几轮原文
```

```java
// 下发顺序：系统提示词 → 长期记忆 → 本次摘要 → 历史。
// 先给全局背景（人设、关于用户的长期事实），再给本次会话背景，最后才是最近对话
out.addAll(memoryItems);
out.addAll(summaryItems);
```

`ContextBudget` 里加了对应的配额层：

```java
public int historyBudget(int systemTokens, int toolTokens,
                         int summaryTokens, int memoryTokens) {
    int used = Math.min(systemTokens, systemReserve)
            + Math.min(toolTokens, toolReserve)
            + Math.min(summaryTokens, summaryReserve)
            + Math.min(memoryTokens, memoryReserve);
    int left = totalTokens - outputReserve - used;
    return Math.max(left, 256);
}
```

注入的文本格式：

```java
private static String format(List<MemoryItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("以下是关于这位用户的长期记忆，回答时自然参考即可，不要复述这些内容：\n");
    for (MemoryItem item : items) {
        sb.append("· [").append(item.getType().getLabel()).append("] ");
        if (!item.getKey().isEmpty()) {
            sb.append(item.getKey()).append("：");
        }
        sb.append(item.getContent()).append('\n');
    }
    return sb.toString();
}
```

"自然参考即可"这一句是有必要的——模型据此把记忆融进回答，照搬原句会显得生硬。

### 7.7 完整流程

```
ContextManager.buildMessages(history, ...)
  │
  ├─ MemoryManager.recallBlock(当前提问)      同步
  │     ├─ MemoryStore.loadAll()
  │     ├─ MemoryRetriever.recall(...)       bigram Jaccard + 遗忘分
  │     ├─ 命中的条目 touch()  → 写回        常用记忆不会被淘汰
  │     └─ format(...)                      渲染成注入文本
  │
  ├─ ContextAssembler.assemble(..., memory) 记忆占一层独立预算
  │
  └─ MemoryManager.maybeExtractAsync(...)   异步（延迟一轮生效）
        ├─ 增量 < 6 条 → 跳过
        ├─ 已在抽取中 → 跳过
        └─ MemoryExtractor.extractAsync(...)
              ├─ 提示词带上"已记住什么"，避免重复抽取
              ├─ 容错解析 JSON
              ├─ MemoryStore.upsert()      同 type+key 覆盖
              └─ 记录抽取进度
```

---

## 8. 流式日志 StreamLogger

调试流式协议有个天然的困境：直接打印每一行原始报文，
每 30ms 就产生一条 200 多字符的日志，其中约 95% 是每行完全相同的协议壳
（`id` / `object` / `created` / `model` / `system_fingerprint`），
真正变化的只有一两个 token。而且 logcat 会把带 `\n` 的内容按行拆开，
一条 JSON 被切成碎片，刷屏的同时结构全丢。

`StreamLogger` 就是为了解决这个：把原始行整理成人能读的块。

### 符号约定

每类信息都用**一对符号包住头尾**，扫符号就知道是什么：

| 符号 | 含义 | 出现形式 |
|---|---|---|
| `★` | 会话开始 / 结束 | `★★★ 流式开始 · SSE · qwen3.5:0.8b ★★★` |
| `♥` | 思考过程块 | `♥♥♥ 思考 块#1 · 128字 ♥♥♥` … `♥♥♥ 思考 块#1 结束 ♥♥♥` |
| `♦` | 正式回答块 | `♦♦♦ 回答 块#2 · 96字 ♦♦♦` … `♦♦♦ 回答 块#2 结束 ♦♦♦` |
| `✦` | 工具调用分片 | `✦✦✦ 工具调用分片 +1（本事件）✦✦✦` |
| `■` | 结束事件 | `■■■ 结束事件 · finish_reason=stop ■■■` |
| `▲` | 告警 | `▲▲▲ 字段 model 在流中途变化：a → b ▲▲▲` |
| `▓` | 原始报文（默认关闭） | `▓▓▓ 完整原始响应 · 42 事件 ▓▓▓` … `▓▓▓ 原始响应结束 ▓▓▓` |

```java
/** 用符号把标题包起来，头尾各三个，扫一眼就能定位。 */
private static String mark(String symbol, String title) {
    return symbol + symbol + symbol + " " + title + " " + symbol + symbol + symbol;
}
```

一次完整会话的输出长这样：

```
★★★ 流式开始 · SSE · qwen3.5:0.8b ★★★
  地址：http://192.168.1.6:11434/v1/chat/completions
  id：chatcmpl-xxx
  created：1759392000

♥♥♥ 思考 块#1 · 128字 · 620ms · 累计128字 ♥♥♥
用户在问时间，我应该调用 get_current_time 工具…
♥♥♥ 思考 块#1 结束 ♥♥♥

✦✦✦ 工具调用分片 +1（本事件）✦✦✦

♦♦♦ 回答 块#2 · 96字 · 480ms · 累计96字 ♦♦♦
现在是 2026年10月02日 星期五 17:23:11。
♦♦♦ 回答 块#2 结束 ♦♦♦

■■■ 结束事件 · finish_reason=stop ■■■

★★★ 流式结束 · 42 事件 · 3200ms · 回答 96 字 · 思考 128 字 ★★★
  finish_reason=stop
  usage：{"prompt_tokens":512,"completion_tokens":224}
```

### 四条压缩原则

1. **协议壳去重**：`id` / `created` / `object` / `system_fingerprint` 只在会话头打一次。
   这是"变简单"的主要来源。
2. **增量合并成块**：连续同类增量先累积，攒到 120 字符打一块。
   几百条 token 日志压成十几条带字数与时长的可读块。
3. **思考与回答分流**：两者各自成块，边界清晰。
4. **内容完整**：超长内容按块切分，日志尾部完整保留。

### 协议壳去重的兜底比对

去重最大的风险是：万一这些字段中途变了呢？
所以每个事件都会重新比对一次，取值一变立刻告警：

```java
/**
 * 检查元信息是否与首事件一致。
 * 只对"本事件确实带了这个字段"的情况比对：字段时有时无是正常行为，
 * 但同一字段两次取值不同就是必须让人看见的信息（换 id、换模型）。
 */
private void checkMetaStable(JSONObject obj) {
    if (obj == null || metaFirst.isEmpty()) {
        return;
    }
    for (String key : META_KEYS) {
        String value = optMetaValue(obj, key);
        if (value == null) {
            continue;
        }
        String first = metaFirst.get(key);
        if (first == null) {
            metaFirst.put(key, value);
            continue;
        }
        if (!first.equals(value) && warned.add("meta:" + key)) {
            sink.log(Level.WARN, PRINT_TAG, mark(M_WARN,
                    "字段 " + key + " 在流中途变化：" + first + " → " + value
                            + "（原文保留在原始响应里）"));
        }
    }
}
```

注意 `warned.add(...)` 的用法——`Set.add` 返回 false 表示已存在，
用它做"每种字段只告警一次"，告警本身因此保持克制。

### 按行边界切分

logcat 单条记录有长度上限，超了会被截断。切分时**必须只在换行处切**：

```java
/**
 * 按上限切分打印，只在行边界切。
 * 按字符硬切会把一条事件断成两半，续接那条以半截 JSON 开头，没法核对。
 */
private void printChunked(String head, String body, String tail) {
    if (body.length() <= CHUNK_CHARS) {
        sink.log(RAW_LEVEL, PRINT_TAG, "\n" + head + "\n\n" + body + endLine(body, tail));
        return;
    }
    List<String> parts = splitAtLineBreak(body, CHUNK_CHARS);
    for (int i = 0; i < parts.size(); i++) {
        String part = parts.get(i);
        StringBuilder out = new StringBuilder();
        if (i == 0) {
            out.append('\n').append(head).append("\n\n");
        } else {
            out.append("…（原文续，本段 ").append(part.length()).append(" 字符）\n");
        }
        out.append(part);
        if (i == parts.size() - 1) {
            out.append(endLine(part, tail));
        }
        sink.log(RAW_LEVEL, PRINT_TAG, out.toString());
    }
}
```

按字符硬切的后果是：续接那条以 `,"created":…` 这种半截 JSON 开头，
归属信息随之丢失，逐条核对报文的线索也就断了。

### 输出后端抽象

```java
/** 日志输出后端（logcat / 文件 / 内存缓冲）。 */
public interface Sink {
    void log(int level, String tag, String text);
}
```

日志逻辑经由 `Sink` 中转（默认用反射调 logcat），与 `android.util.Log` 解耦。
好处是整类可以在 JVM 上跑真实报文做验证——换成内存 `Sink` 就能断言输出内容。

### 两个开关

```java
/** 原始报文是否输出，默认关闭。置 true 后原文在会话结束时一次性输出。 */
public static volatile boolean rawDump = false;

/** true：原文攒到收尾一起打；false：按批边收边打，适合超长流式。 */
public static volatile boolean RAW_TAIL_MODE = true;
```

默认尾随模式是个体验上的选择：流式过程中日志保持连贯可读，
需要核对原文时再往下翻，思路始终连贯。

---

## 9. Markdown 渲染

> 这一块属于 UI，按主题只做简要说明。

Markdown 渲染从解析到排版都在本地实现。分三步：

```
MarkdownParser.parse(source)  →  List<Block>
        │
        ├─ TYPE_TEXT     →  MarkdownInline.apply()  →  StyleSpan(BOLD)
        ├─ TYPE_CODE     →  CodeBlockRenderer.render()
        ├─ TYPE_TABLE    →  TableBlockRenderer.render()
        ├─ TYPE_HEADING  →  按 level 设置字号与字重
        └─ TYPE_LIST     →  按缩进层级渲染
```

```java
public static final int TYPE_TEXT = 0;
public static final int TYPE_CODE = 1;
public static final int TYPE_TABLE = 2;
public static final int TYPE_HEADING = 3;
public static final int TYPE_LIST = 4;
```

两个值得一提的点：

- **表格用 `TextPaint.measureText` 量列宽**，支持 GFM 的三种对齐（`ALIGN_LEFT/CENTER/RIGHT`），
  两种渲染模式（超宽时按比例压缩 vs 保持自然列宽横向滚动）。
- **流式期间走快速通道**。`ChatAdapter` 有独立的渲染路径，
  流式过程中只 `setText`，生成结束后才走一次完整渲染，视图结构保持稳定。

---

## 10. 数据与配置

### 9.1 多套接口配置

`ApiConfigStore` 管理多套 `ApiProfile`，结构是 `profiles` JSON 数组 + `active_id`：

```java
private static final String PREF_NAME = "doudou_api_config";
private static final String KEY_ENABLED = "enabled";
private static final String KEY_PROFILES = "profiles";
private static final String KEY_ACTIVE_ID = "active_id";
```

支持新建 / 重命名 / 删除（至少保留 1 套），并带旧版单配置的迁移逻辑：

```java
/** 旧版本只有单套配置时的键，用于升级迁移。 */
private static final String LEGACY_BASE_URL = "base_url";
private static final String LEGACY_API_KEY = "api_key";
private static final String LEGACY_MODEL = "model";
```

读写都返回**副本**（`profile.copy()`），调用方拿到的是独立对象，单例状态始终安全。

### 9.2 消息模型

`ChatMessage` 一条消息携带的信息：

| 字段 | 说明 |
|---|---|
| `role` | `ROLE_USER` / `ROLE_AI` / `ROLE_TOOL` |
| `content` | 正文 |
| `thinking` | 思考过程，与正文分开存储 |
| `error` | **是否为错误信息**：错误内容仅作本地提示 |
| `toolCallId` | `role=tool` 时对应 `tool_calls` 的 id |
| `toolCalls` | assistant 发起的工具调用（含执行结果） |
| `rawResponse` | API 原始返回，长按气泡可查看 |
| `streaming` | 流式进行中标记（仅内存态） |

`error` 标记是个重要设计：

```java
if (msg.isError()) {
    return null; // 错误信息是本地提示，不能作为上下文回传
}
```

如果把"请求失败：HTTP 500"这类内容回传给模型，模型会以为这是对话的一部分，
后续回答会被污染。所以错误消息统一跳过。

### 9.3 会话持久化

`ConversationStore` 用 `SharedPreferences` 存一个 JSON 数组，
`Conversation` 内部持有 `List<ChatMessage>`，读写时递归序列化。

```java
/** 读取全部会话，按更新时间倒序。 */
public List<Conversation> loadAll() {
    // ...
    Collections.sort(list, new Comparator<Conversation>() {
        @Override
        public int compare(Conversation a, Conversation b) {
            return Long.compare(b.getUpdateTime(), a.getUpdateTime());
        }
    });
    return list;
}
```

标题有两个来源：默认用第一条用户消息截断 16 字兜底，
也可以在设置里打开「自动生成标题」，让模型生成 20 字以内的摘要。

### 9.4 界面偏好

`UiSettingsStore` 目前两个开关：`code_wrap_mode`（代码块换行模式）、
`auto_title_enabled`（自动生成标题）。

---

## 11. 已知坑与解决方案

这一章记录的是真实踩过的坑。每个坑都有对应的代码级处理。

### 10.1 Ollama 的 1024 天花板

**现象**：模型只输出几个字符的思考内容就返回 `[DONE]`，正式回答为空。
日志里表现为 `finish_reason: length`。

**原因**：Ollama 的 `num_predict` 默认只有 1024。思考过程（reasoning）会**先吃掉这份预算**，
预算耗尽后返回 `finish_reason: length`，此时正式回答才刚要开始。

**关键认知**：App 侧「最大输出 Token」**留空即取服务端默认**。
留空意味着该字段整体省略、交由服务端取默认值，Ollama 为 1024。

**解决**：
1. 设置里把「最大输出 Token」填大（如 8192）；
2. 或打开「关闭模型思考」。

代码层面加了专门的检测与提示：

```java
if ("length".equals(finishReason)) {
    // 常见原因：Ollama 的 num_predict 默认只有 1024，
    // 思考过程先把预算吃光，正式回答一个字都没出来
    Log.w(LOG_TAG, "输出被截断：服务端返回 finish_reason=length。"
            + "可在接口设置里把「最大输出 Token」填大（如 8192），"
            + "或关掉模型思考");
}
```

### 10.2 关闭思考必须用 `reasoning_effort: "none"`

`"think": false` 只在部分服务端生效。现在统一下发：

```java
if (config.isDisableThinking()) {
    body.put("reasoning_effort", "none");
}
```

另外这个开关必须**立即持久化**。用户反馈过"关掉之后下次进来又恢复原状"，
原因是设置页在校验失败时会提前 return，开关的落盘被跳过。
现在的处理是**开关写入排在校验之前**。

### 10.3 读超时会误判思考型模型

**现象**：思考型模型思考十几分钟，60 秒时被判超时。

**解决**：`READ_TIMEOUT_MS = 0`（读取时长放开），配套用 `MAX_RAW_CHARS = 1MB` 兜底内存。
需要中断时用界面上的「停止」按钮主动断开。

### 10.4 工具调用与结果必须成组

裁剪历史时如果只留下 `assistant` 的 `tool_calls` 而丢掉对应的 `role=tool` 结果，
多数服务端直接返回 400。上下文模块用「按组裁剪」解决，见 [6.4](#64-按组裁剪算法)。

### 10.5 错误信息污染上下文

请求失败的消息会被标记 `error`，序列化时跳过。
新增任何错误路径时都要带上这个标记。

### 10.6 配置切换时开关被覆盖

**现象**：切换 API 配置后，某些开关变成旧值。

**原因**：设置页 `bindForm()` 回填表单时，`setChecked` 会触发 `OnCheckedChangeListener`
里的 `persistToggles()`，把**另外两个待回填开关的旧值**写进新配置。

**解决**：加 `bindingForm` 标志位，回填期间屏蔽回调。

```java
// 回填期间 setChecked 会触发回调，用标志位屏蔽，避免把旧值写进新配置
```

以后给设置页加开关时，新开关也要走 `persistToggles()` 和 `bindFormInternal()`。

### 10.7 流式输出导致卡顿 / 气泡被顶飞

已修，以下改动点需要保持：

- `RecyclerView.setItemAnimator(null)`、`setItemViewCacheSize(8)`
- 流式走快速通道：`bindStreamingLayout()` + `updateStreamingContent()`
- 思考块流式期间 `setMaxLines(6)` + 只显示最后 400 字符，
  **选择 `TruncateAt.END`**（`TruncateAt.START` 会让文本反复重排）
- 节流 80ms；`autoScrollToBottom` 由 `canScrollVertically(1)` 判定，
  用户手动上滑就停止自动跟随

---

## 12. 设计取舍

| 取舍 | 选择                         | 理由 |
|---|----------------------------|---|
| 网络库 | `HttpURLConnection`        | 零三方依赖；流式读取行为完全可控 |
| JSON | Android 自带 `org.json`      | 同上，代价是 API 比较啰嗦 |
| LLM 框架 | Original                   | 需要精确控制上下文预算和状态机，让控制点保持显式 |
| 回调传值 | 传累计全文                      | UI 侧丢帧仍保持内容完整，状态简单 |
| 线程模型 | 单线程池                       | 一次对话同时只有一个请求，够用且状态清晰 |
| 压缩时机 | 延迟一轮生效                     | 换来了引擎异步流程零改动；压缩失败也与对话链路隔离 |
| 工具能力 | 只做只读                       | 小模型判断力有限，只读操作的后果可控 |
| 历史裁剪 | 按组                         | tool_calls 与结果同进同退，规避 400 |
| 记忆检索 | bigram Jaccard             | 端侧调 embedding 多一次网络往返且依赖服务端能力；bigram 零依赖可解释 |
| 记忆冲突 | 覆盖                         | 两条矛盾信息比零信息更有害 |
| 抽取时机 | 后台异步、延迟一轮                  | 与压缩同一节拍；失败与对话链路隔离 |
| 持久化 | `SharedPreferences` + JSON | 数据量小（几十个会话、两百条记忆），轻量方案刚好够用 |

**关于协议与调度**：目的很直接。上下文预算和工具调用状态机恰恰是
最需要精细控制的两个地方，而框架的默认抽象（比如自动管理 messages）
会把这些控制点藏起来。每一层 token 怎么算、哪一条被丢掉，都是显式的。

---

## 13. 路线图

按「上下文工程 → Agent 能力 → 评测」的顺序推进。

### 已完成

- [x] SSE / NDJSON 双协议流式解析
- [x] Function Calling 多轮循环
- [x] 上下文分层预算与按组裁剪
- [x] 滚动摘要压缩（只压增量）
- [x] 三层记忆系统（抽取、冲突消解、遗忘、bigram 召回）
- [x] 流式日志整理

### 进行中 / 规划

| 优先级 | 项目 | 说明 |
|---|---|---|
| P0 | 护栏 | 死循环检测、token 预算熔断、危险操作确认 |
| P1 | ReAct 状态机 | 把「推理 → 行动 → 观察 → 反思」显式分阶段，每阶段独立预算 |
| P1 | Plan-and-Execute | 复杂任务先出计划，执行中动态增删改，解决"跑偏忘目标" |
| P1 | Eval 评测集 | 30~50 条任务 + 全链路 Trace，量化改动效果 |
| ~~P2~~ | ~~记忆系统~~ | ✅ 已完成，见[第 7 章](#7-记忆系统三层记忆) |
| P2 | Agentic RAG | 混合检索 + Rerank + 检索自纠，把检索做成模型可调度的工具 |
| P3 | MCP Client | 远端工具动态发现与注册，与本地工具统一调度 |
| P3 | Sub-agent 编排 | 子 Agent 上下文隔离，结果摘要回传 |

---

## 14. 附录：关键代码索引

| 想看什么 | 文件 | 位置 |
|---|---|---|
| 请求体构造 | `ai/OpenAiEngine.java` | `buildRequestBody()` |
| SSE 事件解析 | `ai/OpenAiEngine.java` | `parseEvent()` / `ParsedEvent` |
| Ollama 兼容 | `ai/OpenAiEngine.java` | `parseNativeChunk()` / `isNativeDone()` |
| 工具分片合并 | `ai/OpenAiEngine.java` | `mergeToolCallDeltas()` / `ToolCallBuilder` |
| 多轮工具循环 | `ai/OpenAiEngine.java` | `doRequest()` |
| usage 提取 | `ai/OpenAiEngine.java` | `extractUsage()` |
| 工具声明 | `ai/ToolRegistry.java` | `toolsJson()` / `execute()` |
| 上下文裁剪 | `ai/context/ContextAssembler.java` | `assemble()` / `group()` |
| 分层预算 | `ai/context/ContextBudget.java` | `historyBudget()` |
| token 估算 | `ai/context/TokenEstimator.java` | `estimate()` |
| 滚动压缩 | `ai/context/RollingCompressor.java` | `compactAsync()` / `SUMMARY_PROMPT` |
| 摘要持久化 | `ai/context/SummaryStore.java` | `load()` / `save()` |
| 上下文门面 | `ai/context/ContextManager.java` | `buildMessages()` |
| 记忆类型 | `ai/memory/MemoryType.java` | 三层定义与半衰期 |
| 记忆条目 | `ai/memory/MemoryItem.java` | `score()` 遗忘评分 |
| 记忆存储 | `ai/memory/MemoryStore.java` | `upsert()` 冲突消解 / `trim()` 淘汰 |
| 记忆检索 | `ai/memory/MemoryRetriever.java` | `recall()` / `similarity()` |
| 记忆抽取 | `ai/memory/MemoryExtractor.java` | `EXTRACT_PROMPT` / `parse()` |
| 记忆门面 | `ai/memory/MemoryManager.java` | `recallBlock()` / `maybeExtractAsync()` |
| 流式日志 | `ai/StreamLogger.java` | `onEvent()` / `flushBlock()` / `mark()` |
| 消息模型 | `model/ChatMessage.java` | `toJson()` / `fromJson()` |
| 接口配置 | `data/ApiConfigStore.java` | `getActiveProfile()` |

### 模块与行数

| 模块 | 文件数 | 行数 | 说明 |
|---|---|---|---|
| `ai/` | 6 | 1671 | 引擎、工具、日志 |
| `ai/context/` | 8 | 987 | 上下文工程 |
| `ai/memory/` | 6 | 928 | 三层记忆 |
| `data/` | 4 | 539 | 配置与持久化 |
| `model/` | 4 | 593 | 数据模型 |
| `ui/` | 11 | 3152 | 界面与 Markdown 渲染 |
| **合计** | **39** | **7870** | |

---

## License

[MIT](LICENSE) © 2026 sreCP

可以自由使用、修改与分发，保留版权声明即可。


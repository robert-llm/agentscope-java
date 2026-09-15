# AgentScope Java 2.0 系统学习指南

> 从架构全景到源码精读，分五个阶段逐步掌握这个企业级智能体框架
>
> 基于 v2.0.3-SNAPSHOT 源码分析生成

---

## 目录

1. [项目概览与核心理念](#0-项目概览与核心理念)
2. [第一阶段：理解全局架构（1-2 天）](#1-第一阶段理解全局架构1-2-天)
3. [第二阶段：掌握 agentscope-core（3-5 天）](#2-第二阶段掌握-agentscope-core3-5-天)
4. [第三阶段：掌握 agentscope-harness（5-7 天）](#3-第三阶段掌握-agentscope-harness5-7-天)
5. [第四阶段：扩展生态（3-5 天）](#4-第四阶段扩展生态3-5-天)
6. [第五阶段：高级主题与综合实战（持续）](#5-第五阶段高级主题与综合实战持续)
7. [附录：agentscope-paw 解读](#附录agentscope-paw-解读)
8. [学习资源汇总](#学习资源汇总)

---

## 0. 项目概览与核心理念

AgentScope Java 2.0 是阿里巴巴开源的企业级、分布式、生产就绪的智能体框架。核心采用**双层架构**：

- **agentscope-core** — 裸 ReAct 推理循环，轻量无依赖
- **agentscope-harness** — 在 core 之上叠加工程化能力（Workspace、Memory、Middleware、子 Agent、状态持久化等）

这意味着：

- 如果你只需要一个简单的 Agent，用 `agentscope-core` 就够了
- 如果你需要 Workspace、Memory、Session 持久化、子 Agent 编排等生产级能力，引入 `agentscope-harness`

> 💡 **先跑示例，再读源码。** `agentscope-examples/documentation` 中有 50 个精心编写的示例，覆盖所有核心特性，是最好的起点。

---

## 1. 第一阶段：理解全局架构（1-2 天）

### 1.1 Maven 模块结构

| 模块 | 职责 |
|------|------|
| **agentscope-core** | 核心抽象：Agent 接口、消息模型、事件系统、Tool、Memory、Middleware 基础 |
| **agentscope-harness** | 工程化层：HarnessAgent、Workspace、文件系统、Gateway、Session 管理 |
| **agentscope-extensions** | 扩展插件：模型提供商、数据库、RAG、Channel（钉钉/飞书/企微）等 |
| **agentscope-examples** | 示例代码：documentation 示例、copilotkit、agui、paw 等 |
| **agentscope-service** | Agent 控制面：注册、发现、Dashboard |
| **agentscope-dependencies-bom** | 依赖版本管理 |
| **agentscope-distribution** | 发布打包 |

> **关键设计决策**：`agentscope-harness` 只依赖 `agentscope-core`，这意味着 core 是完全独立的，你可以只用 core 来构建轻量级 Agent。

### 1.2 核心接口分层设计

这是整个框架最精妙的设计之一。理解了这个接口层次，就理解了框架的设计哲学：

```
                ┌─────────────────────┐
                │      Agent          │  ← 顶层接口（完整能力）
                │  getAgentId()       │
                │  getName()          │
                │  interrupt()        │
                │  getToolkit()       │
                │  getAgentState()    │
                └──────┬──────────────┘
                       │ extends
          ┌────────────┼────────────────┐
          │            │                │
┌─────────▼──────┐  ┌──▼────────────┐  ┌▼────────────────┐
│  CallableAgent │  │ StreamableAgent│  │ ObservableAgent │
│                │  │ (deprecated)   │  │                 │
│ call(Msg)      │  │ stream(Msg)    │  │ observe(Msg)    │
│ call(String)   │  │ streamEvents() │  │ 接收消息但不回复  │
│ call(List<Msg>)│  │  → Flux<Event> │  │ 用于多Agent协作  │
│ → Mono<Msg>    │  └───────────────┘  └─────────────────┘
└────────────────┘
```

三个子接口各有清晰职责：

- **CallableAgent** — 处理消息并返回响应，核心方法 `Mono<Msg> call(List<Msg> msgs)`
- **StreamableAgent** — 流式输出执行过程事件（v1 遗留，v2 推荐用 `ReActAgent.streamEvents()`）
- **ObservableAgent** — 被动观察消息，不产生回复，用于多 Agent 协作场景

### 1.3 实现类层次

```
Agent (接口)
  └── AgentBase (抽象基类, 1040行)
        ├── 提供：Hook 管理、中断机制、Tracing、状态管理
        ├── 不提供：Memory 管理（交给子类）
        │
        └── ReActAgent (核心实现, 5355行)
              ├── Reasoning → Acting → Observation 循环
              ├── Memory 管理
              ├── Tool 执行
              ├── Middleware 链
              ├── 权限引擎
              └── 事件流发射

HarnessAgent (harness 层, 2922行)
  └── 内部持有 ReActAgent，通过 Middleware 叠加：
        ├── Workspace 文件系统
        ├── 分层 Memory
        ├── 子 Agent 编排
        ├── Skill 技能仓库
        ├── Session 持久化
        └── 上下文自动压缩
```

**AgentBase 的设计哲学**：
- AgentBase 提供基础设施（hooks, subscriptions, interrupt, state），但不提供领域逻辑
- Memory 管理委托给具体需要的 Agent 实现（如 ReActAgent）

### 1.4 消息模型 — ContentBlock 体系

框架使用 Java 17 的 `sealed class` 来定义内容块类型系统：

```
ContentBlock (sealed)
  ├── TextBlock        — 纯文本
  ├── ThinkingBlock    — Agent 思考/推理内容
  ├── ImageBlock       — 图片（URL 或 Base64）
  ├── AudioBlock       — 音频
  ├── VideoBlock       — 视频
  ├── ToolUseBlock     — 工具调用请求
  ├── ToolResultBlock  — 工具执行结果
  ├── HintBlock        — RAG/记忆等提示
  └── DataBlock        — 通用二进制数据
```

`sealed` 关键字意味着编译器可以做穷举检查，这是 Java 17 的模式匹配特性。

### 1.5 事件系统 — 31 种类型化事件

| 类别 | 事件 | 说明 |
|------|------|------|
| Agent 生命周期 | `AGENT_START`, `AGENT_END`, `AGENT_RESULT` | Agent 开始/结束/产出结果 |
| 模型调用 | `MODEL_CALL_START`, `MODEL_CALL_END` | LLM API 调用 |
| 文本流 | `TEXT_BLOCK_START/DELTA/END` | 文本增量输出 |
| 思考流 | `THINKING_BLOCK_START/DELTA/END` | 推理过程输出 |
| 数据流 | `DATA_BLOCK_START/DELTA/END` | 二进制数据流 |
| 工具调用 | `TOOL_CALL_START/DELTA/END` | 工具调用过程 |
| 工具结果 | `TOOL_RESULT_START/TEXT_DELTA/DATA_DELTA/END` | 工具执行结果 |
| HITL | `REQUIRE_USER_CONFIRM`, `USER_CONFIRM_RESULT` 等 | 人机交互 |
| 其他 | `HINT_BLOCK`, `CUSTOM`, `SUBAGENT_EXPOSED` 等 | 扩展场景 |

### 1.6 第一个示例

```java
// 1. 构建 Agent — 只需 4 行
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model("dashscope:qwen-plus")     // 自动从环境变量读取 API Key
    .toolkit(new Toolkit())
    .build();

// 2. 流式调用 — 监听事件
agent.streamEvents(userMsg)
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());  // 增量文本
        }
    })
    .blockLast();
```

**关键观察**：
- `model("dashscope:qwen-plus")` — 通过字符串指定模型，`ModelRegistry` 自动解析提供商并读取环境变量
- `streamEvents()` 返回 `Flux<AgentEvent>` — 所有执行过程都作为事件流出
- `TextBlockDeltaEvent` — 文本增量事件，用于实时渲染

---

## 2. 第二阶段：掌握 agentscope-core（3-5 天）

### 2.1 消息模型深入

#### Msg 的核心字段

```java
public class Msg implements State {
    private final String id;                    // 唯一标识
    private final String name;                  // 发送者名称
    private final MsgRole role;                 // USER / ASSISTANT / SYSTEM / TOOL
    private final List<ContentBlock> content;   // 内容块列表（不可变）
    private final Map<String, Object> metadata; // 扩展元数据
    private final String timestamp;             // 时间戳
    private final ChatUsage usage;              // Token 消耗统计
}
```

**关键设计决策**：
- **Content 是不可变 List** — `toList()` 保证线程安全
- **Role 严格校验** — 构造函数调用 `validateRoleContent()` 确保角色与内容块匹配
- **Metadata 是开放 Map** — 用于承载 HITL 确认结果、合成标记、结构化输出等框架级数据

#### Role 与 ContentBlock 的约束规则

| Role | 允许的 ContentBlock 类型 |
|------|------------------------|
| `USER` | TextBlock, DataBlock, ImageBlock, AudioBlock, VideoBlock |
| `SYSTEM` | TextBlock, HintBlock |
| `ASSISTANT` | TextBlock, ThinkingBlock, ToolUseBlock, DataBlock |
| `TOOL` | ToolResultBlock（不限制） |

#### 消息子类

框架按 Role 提供了 4 个消息子类，每个都通过 Builder 锁定 role 不可变：

- `UserMessage.java` — `new UserMessage("你好")` 最常用
- `AssistantMessage.java` — 模型生成的回复
- `SystemMessage.java` — 系统提示
- `ToolResultMessage.java` — 工具执行结果

### 2.2 工具系统

#### AgentTool 接口

```java
public interface AgentTool {
    String getName();                    // 工具名称（snake_case 约定）
    String getDescription();            // 描述（帮助 LLM 决定何时调用）
    Map<String, Object> getParameters(); // JSON Schema 参数定义
    boolean isReadOnly();               // 是否只读（Plan Mode 下自动放行）
    Mono<ToolResultBlock> callAsync(ToolCallParam param); // 异步执行
}
```

#### Toolkit — 工具管理门面

`Toolkit`（1061 行）是工具系统的统一入口，内部委托给多个专业管理器：

```
Toolkit (门面)
  ├── ToolRegistry        — 工具注册与查找
  ├── ToolGroupManager    — 工具组 CRUD + 活跃组管理
  ├── ToolSchemaProvider  — 生成 JSON Schema（按组过滤）
  ├── McpClientManager    — MCP 客户端生命周期管理
  ├── MetaToolFactory     — 创建 meta 工具（动态组控制）
  └── ToolExecutor        — 并行/串行工具执行 + 校验
```

#### 工具注册方式

- **注解扫描** — `toolkit.registerTool(myObj)` 扫描 `@Tool` 注解方法
- **直接注册** — `toolkit.registerAgentTool(agentTool)` 注册 AgentTool 实现
- **MCP 客户端** — `toolkit.registration().mcpClient(mcpWrapper).apply()` 接入 MCP 工具
- **子 Agent 工具** — 通过 SubAgentProvider 将子 Agent 包装为工具

### 2.3 ReAct 推理循环 — 核心中的核心

`ReActAgent.java`（5356 行）是整个框架的心脏。

#### 执行入口

```
call(msgs) ──────► callInternal() ──────► buildAgentStream()
                                              │
streamEvents(msgs) ──────────────────────────►│
                                              │
                                    ┌─────────▼──────────┐
                                    │  AgentStartEvent    │
                                    │  ↓                  │
                                    │  runLifecycle()     │
                                    │  ↓                  │
                                    │  doCall()           │
                                    │  ↓                  │
                                    │  AgentResultEvent   │
                                    │  ↓                  │
                                    │  AgentEndEvent      │
                                    └─────────────────────┘
```

> **关键设计**：`call()` 和 `streamEvents()` 共享同一个 `buildAgentStream()` 核心，保证 `onAgent` Middleware 链在所有调用路径上都会触发。

#### 核心 ReAct 循环

```
doCallInner(msgs)
    │
    ├── 检查 pending tool calls（恢复机制）
    │   ├── 有 ASKING 工具 → 等待 HITL 确认
    │   ├── 有 pending 工具 + 无结果 → 自动修补
    │   └── 有 pending 工具 + 有结果 → 验证并继续
    │
    └── coreAgent() ──► executeIteration(0)
                            │
                ┌───────────▼───────────┐
                │    reasoning(iter)     │ ← 推理阶段
                │  1. 检查 maxIters      │
                │  2. firePreReasoning   │
                │  3. onReasoning MW链   │
                │  4. model.stream()     │ ← 调用 LLM
                │  5. 累积 chunk         │
                │  6. firePostReasoning  │
                │  7. 判断下一步          │
                │     ├── HITL stop?     │
                │     ├── finished?      │
                │     └── → acting()     │
                └───────────┬───────────┘
                            │
                ┌───────────▼───────────┐
                │     acting(iter)       │ ← 行动阶段
                │  1. 提取 pending 工具  │
                │  2. firePreActing      │
                │  3. onActing MW 链     │
                │  4. 执行工具           │
                │  5. firePostActing     │
                │  6. 判断下一步          │
                │     ├── stop?          │
                │     ├── suspended?     │
                │     └── → reasoning()  │ ← 回到推理
                └───────────────────────┘
```

核心代码位于第 2263-2800 行：

```java
// 第 2268 行 — 入口
private Mono<Msg> coreAgent() {
    return executeIteration(0);
}

// 第 2280 行 — 迭代
private Mono<Msg> executeIteration(int iter) {
    return reasoning(iter, false);
}

// 第 2294 行 — 推理阶段
private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
    if (!ignoreMaxIters && iter >= maxIters) {
        return summarizing();  // 超过最大迭代 → 总结
    }
    // ... 调用模型 → 累积 chunk → 判断是否有工具调用
    // 有工具调用 → acting(iter)
    // 无工具调用 → 返回最终结果
}

// 第 2717 行 — 行动阶段
private Mono<Msg> acting(int iter) {
    // ... 执行工具 → 收集结果 → 添加到上下文
    // → executeIteration(iter + 1) 回到推理
}
```

#### Middleware 链在循环中的位置

每一阶段都被 Middleware 链包裹，形成 5 个拦截点：

```
onAgent ──────────────────────────────────────────────┐
  │                                                    │
  onSystemPrompt ──► 修改系统提示                       │
  │                                                    │
  onReasoning ─────────────────────────────┐           │
    │                                      │           │
    onModelCall ──► 拦截模型调用            │           │
    │              (可替换 model/messages)   │           │
    │                                      │           │
  onActing ──► 拦截工具执行                 │           │
    │          (可替换工具列表)              │           │
    │                                      │           │
  └──────────────────────────────────────┘           │
                                                     │
  └─────────────────────────────────────────────────┘
```

### 2.4 权限系统与 HITL

权限引擎采用**三态决策**模型：

- **ALLOW** → 工具直接执行
- **ASKING** → 暂停，等待人类确认（HITL）
- **DENY** → 工具被拒绝，写入 "Permission denied" 结果

#### HITL 流程

1. 模型请求调用工具 → 权限引擎评估
2. 如果是 ASKING → Agent 发出 `RequireUserConfirmEvent` → 暂停
3. 用户通过第二次 `call(msgs)` 携带 `ConfirmResult` 恢复
4. `ConfirmResult(true)` → 工具状态变为 ALLOWED → 执行
5. `ConfirmResult(false)` → 写入 DENIED 结果 → 继续循环

### 2.5 状态管理与 Session 持久化

ReActAgent 使用 `(userId, sessionId)` 作为 slot 键，每个 slot 独立维护：

- **AgentState** — 对话上下文 + 工具状态 + 权限上下文
- **PermissionEngine** — 独立的权限引擎实例
- **slotVersions** — 乐观并发控制版本号

```java
// 第 397 行 — slot 键格式
private static String slotKey(String userId, String sessionId) {
    return (userId == null ? "__anon__" : userId) + "/" + sessionId;
}
```

当配置了 `AgentStateStore` 时，每次 call 开始都会从存储重新加载状态，保证分布式部署下的一致性。

### 第二阶段学习检查清单

完成第二阶段后，你应该能回答：

1. **消息模型**：`Msg` 的 `content` 为什么是不可变 List？（线程安全）
2. **Role 校验**：USER 消息能包含 ToolUseBlock 吗？（不能，只允许 text/data/image/audio/video）
3. **ReAct 循环**：reasoning → acting → reasoning 的循环何时终止？（无工具调用 / 达到 maxIters / HITL stop / middleware stop）
4. **Middleware**：5 个拦截阶段分别在什么时候触发？
5. **HITL**：权限 ASKING 状态如何暂停和恢复 Agent？
6. **call() vs streamEvents()**：它们共享什么核心？（`buildAgentStream()`）

---

## 3. 第三阶段：掌握 agentscope-harness（5-7 天）

### 3.1 HarnessAgent

精读 `HarnessAgent.java`（2922 行）：

- 理解它如何在 ReActAgent 之上叠加工程化能力
- 关注 Builder 模式和配置方式

### 3.2 Workspace 与文件系统

阅读 `agentscope-harness/.../filesystem/` 包：

- `CompositeFilesystem.java` — 组合文件系统
- `LocalFilesystem.java` — 本地文件系统
- `BaseSandboxFilesystem.java` — 沙箱文件系统

### 3.3 Memory 与 Session 管理

阅读 `agentscope-harness/.../memory/` 包：

- `MemoryConfig.java` — 记忆配置
- `SessionTree.java` — Session 树管理
- `ConversationCompactor.java` — 上下文压缩

### 3.4 Middleware 机制

理解 5 个拦截阶段：onAgent / onReasoning / onActing / onModelCall / onSystemPrompt，关注 Middleware 链的编排方式。

### 3.5 子 Agent 编排

- `agent_spawn` / `agent_send` 机制
- 同步与异步委派模式

### 3.6 Gateway 与 Channel

- `Gateway.java` — 网关接口
- `HarnessGateway.java` — 网关实现
- `Channel.java` — Channel 抽象（钉钉/飞书/企微）

### 3.7 实践：按顺序运行 harness 示例

1. `WorkspaceSetupExample.java` — Workspace 基础
2. `MemoryCompactionExample.java` — 记忆压缩
3. `SubagentSendDirectlyExample.java` — 子 Agent
4. `PlanModeAutoExample.java` — Plan Mode

---

## 4. 第四阶段：扩展生态（3-5 天）

### 4.1 模型提供商

了解 `agentscope-extensions-model-*` 的模块化设计：

- `model-openai` / `model-dashscope` / `model-anthropic` / `model-gemini` / `model-ollama`
- 阅读其中一个实现，理解 ChatModel 接口的适配方式

### 4.2 状态持久化

- `agentscope-extensions-redis` — Redis 状态存储
- `agentscope-extensions-mysql` / `agentscope-extensions-postgresql` — 关系型数据库
- `agentscope-extensions-oss` / `agentscope-extensions-cos` — 对象存储

### 4.3 RAG 与 Knowledge

- 理解 Knowledge 接口和 RAG 模式
- 了解 dify / ragflow / haystack 等集成

### 4.4 协议支持

- A2A（Agent-to-Agent）协议
- AG-UI 协议
- Agent Protocol

---

## 5. 第五阶段：高级主题与综合实战（持续）

### 5.1 AgentScope Service

- 控制面架构
- Agent 注册与发现
- Dashboard 使用

### 5.2 分布式部署

- 无状态水平扩展方案
- Session 恢复与跨副本路由
- 阅读 `DistributedStore.java`

### 5.3 综合实战项目（递进练习）

1. 用 `agentscope-core` 写一个纯 ReAct Agent（带自定义 Tool）
2. 升级到 `agentscope-harness`，加上 Workspace + Memory + Skill
3. 实现多 Agent 协作场景（子 Agent 编排）
4. 接入 Channel（钉钉/飞书），做一个可交互的聊天机器人
5. 部署为分布式服务，使用 Redis/MySQL 做状态持久化

---

## 附录：agentscope-paw 解读

### 什么是 agentscope-paw？

**AgentScope Paw** 是 QwenPaw 的 Java 版本 — 一款装在你自己电脑上的个人助手。它以你的身份、在你的文件系统和 Shell 里干活，并且会随着使用慢慢"长大"。

| | paw |
|---|---|
| **适用场景** | 在自己笔记本/工作站上的个人助手 |
| **用户数** | 1 人 — 你自己 |
| **隔离** | 无 — 直接以你的身份运行，可访问你的 Shell |
| **自进化** | ✅ 技能、子智能体、记忆、AGENTS.md 都是 agent 自己写的工作区文件 |
| **通道** | 内置 Web UI + 钉钉 · 企业微信 · 飞书 · GitHub · GitLab |
| **分布式** | ❌ 单进程、单节点 |
| **文件系统** | LocalFilesystemWithShell — 直连本机 FS + Shell |

### 架构

paw 是一个轻量的 Spring Boot 应用，把 **HarnessAgent** 直接挂载到 `LocalFilesystemWithShell` 之上。

```
┌─────────────────────────────────────────────────────────────────┐
│                          你的本机                               │
│  ┌─────────────────────┐   ┌─────────────────────────────────┐  │
│  │  通道适配           │   │  HarnessAgent（每个 agent 一个）│  │
│  │  ├ chatui (Web UI)  │──▶│   ├ 推理（LLM）                 │  │
│  │  ├ dingtalk 钉钉    │   │   ├ Skills · Sub-agents · MCP   │  │
│  │  ├ wecom · feishu   │   │   └ 自进化循环                  │  │
│  │  └ github · gitlab  │   └────────────┬────────────────────┘  │
│  └─────────────────────┘                ▼                       │
│                          ┌──────────────────────────────────┐   │
│                          │  LocalFilesystemWithShell        │   │
│                          │   ├ 本机 FS（~/.agentscope/...） │   │
│                          │   └ 本机 Shell（bash / zsh）     │   │
│                          └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 与其他模块的关系

| 项目 | 定位 |
|------|------|
| **agentscope-paw** | 个人助手（单用户、本机运行） |
| **agentscope-service** | 团队/企业版（多租户、分布式、Dashboard） |
| **agentscope-dataagent** | 数据 Agent（需要沙箱隔离的场景） |

---

## 学习资源汇总

| 资源 | 链接/路径 |
|------|-----------|
| 官方中文文档 | https://java.agentscope.io/v2/zh/intro.html |
| README 中文 | `README_zh.md` |
| 示例代码（最推荐） | `agentscope-examples/documentation/src/main/java/.../documentation2/` |
| 快速入门示例 | `quickstart/BasicChatExample.java` |
| DeepWiki 问答 | https://deepwiki.com/agentscope-ai/agentscope-java |
| Discord 社区 | https://discord.gg/eYMpfnkG8h |

---

## 学习建议

1. **先跑示例，再读源码** — 50 个示例覆盖所有核心特性，是最好的起点
2. **从 core 到 harness** — 先理解底层 ReAct 循环，再理解上层工程化封装
3. **关注接口设计** — 接口分层非常清晰（Agent → CallableAgent + StreamableAgent + ObservableAgent），理解接口就理解了设计哲学
4. **边学边写** — 每学完一个模块，动手写一个小示例验证理解

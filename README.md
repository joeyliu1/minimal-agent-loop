# Minimal Agent Loop

一个用于学习和实现 AI Agent 核心机制的轻量级项目。

项目基于 Spring Boot、Spring AI Alibaba 和通义千问，实现了一个可运行的 ReAct 风格 Agent Loop。你可以通过网页实验室发起任务、观察 Agent Loop 的基本阶段，并学习工具调用、对话记忆、失败重试和运行指标等能力。

> 这个项目优先考虑结构清晰和便于学习，不以生产级 Agent 平台为目标。

## 项目特性

- ReAct 风格 Agent Loop：模型可以直接回答，也可以调用工具后继续推理
- 基于状态机管理 `THINKING`、`TOOL_CALLING`、`RESPONDING` 和异常状态
- 内置计算、日期、本地文件读取和模拟搜索工具
- 使用 MySQL 持久化最近的对话上下文
- 支持会话列表、历史消息读取和会话删除
- LLM 与工具调用失败重试
- Agent 总超时、最大步骤数和文件读取范围限制
- Micrometer、Prometheus 和 Actuator 运行指标
- 面向学习场景设计的响应式 Web 实验室

## Agent 学习实验室

启动项目后访问 [http://localhost:8085](http://localhost:8085)，即可进入学习页面。

页面由三个主要区域组成：

| 区域 | 作用 |
| --- | --- |
| 学习路径与实验记录 | 展示 Agent 学习顺序，并管理历史会话 |
| Agent Playground | 输入任务、查看用户与 Agent 的对话结果 |
| Loop 观察器 | 演示 `Observe → Think → Act → Answer` 的基本执行阶段 |

学习路径聚焦 Agent 自身能力。它不是静态目录，每个阶段都是一个可以操作的微型课程：

| 课程 | 学习重点 | 实验方式 | 关联源码 |
| --- | --- | --- | --- |
| 理解 Agent Loop | 推理、行动和状态变化 | 对比直接回答与工具调用路径 | `AgentOrchestrator`、`AgentContext` |
| 探索工具调用 | 工具选择、参数和结果回填 | 分别触发计算、日期和文件工具 | `MathTool`、`FileReadTool`、`ResilientToolExecutor` |
| 加入对话记忆 | session 隔离与上下文恢复 | 用两轮对话写入并验证实验代号 | `ChatMemoryService`、`AgentService` |
| 观察执行与容错 | 最大步骤、超时、重试和指标 | 主动触发文件错误并分析运行边界 | `AgentProperties`、`AgentMetrics` |

点击任一课程后，页面会同步更新：

- 中间课程标题、学习摘要、预计时间和关联源码
- 本节专属的实验任务卡片与主实验提示词
- 右侧学习目标和核心概念说明
- “载入实验任务”按钮会把推荐任务直接写入输入框
- “标记完成”会更新左侧完成状态，并通过浏览器本地存储保留学习进度

页面同时支持：

- 用户消息右侧显示，Agent 消息左侧显示
- 新建、切换、清空和删除实验会话
- 四节可切换课程及独立的动手实验
- 一键载入实验提示词和持久化课程完成状态
- Markdown 风格回答、代码块和长文本换行
- 通过 SSE 实时推送 Agent 状态和模型 token，完成后恢复 Markdown 渲染
- Agent 执行中的加载状态与 Loop 阶段动画
- 桌面、平板和移动端响应式布局

> 当前 Loop 观察器是帮助理解执行流程的前端教学动画，并非后端逐步骤实时事件。若需要展示真实工具名称、参数、耗时和执行结果，可以在后续接入 SSE 或 WebSocket 事件流。

## 你可以学到什么

1. 如何使用 `ChatClient.defaultTools(...)` 注册模型可调用工具
2. 如何组织“模型推理 → 工具调用 → 结果回填 → 继续推理”的 Agent Loop
3. 如何用 `AgentContext` 保存一次执行的消息、步骤和状态
4. 如何限制最大步骤数、总执行时间和工具访问范围
5. 如何实现 LLM 与工具调用的失败重试
6. 如何从 MySQL 恢复最近会话上下文
7. 如何记录 Agent 步骤、工具和执行耗时指标
8. 如何为 Agent 构建一个面向学习的交互页面

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Web / REST API | Spring Boot 3.3 |
| LLM | Spring AI Alibaba DashScope |
| 模型 | 通义千问 |
| Agent 工具调用 | Spring AI Tool Calling |
| 对话记忆 | MySQL + JdbcTemplate |
| 容错 | Resilience4j |
| 指标 | Actuator + Micrometer + Prometheus |
| 前端 | 原生 HTML、CSS、JavaScript |
| Java | 17+ |

## Agent Loop 工作方式

一次对话请求的执行链路如下：

```text
浏览器 POST /api/chat/stream
  └─ WebController.chatStream(...)
      └─ AgentService.executeStreaming(...)
          ├─ 创建 AgentContext
          ├─ 加入当前 UserMessage
          └─ AgentOrchestrator.executeStreaming(...)
              ├─ 读取最近 20 条会话消息
              ├─ 调用 LLM
              ├─ 是否产生工具调用？
              │   ├─ 是：执行工具并把结果加入上下文
              │   │      └─ 回到 LLM 继续推理
              │   └─ 否：生成最终回答
              └─ 保存用户消息与 Agent 回答到 MySQL
```

核心循环可以简化为：

```text
THINKING
   │
   ├─ 没有 tool call ──> RESPONDING ──> 返回答案
   │
   └─ 存在 tool call ──> TOOL_CALLING
                              │
                              └─ 工具结果加入上下文 ──> THINKING
```

循环会在以下情况终止：

- 模型生成不包含工具调用的最终回答
- 达到 `agent.max-steps`
- 超过 `agent.timeout-seconds`
- LLM 或工具执行发生不可恢复错误

## 内置工具

| 工具名 | 实现类 | 用途 |
| --- | --- | --- |
| `calculator` | `MathTool` | 执行数学表达式计算 |
| `get_date` | `CurrentDateTool` | 获取当前日期和时间 |
| `read_file` | `FileReadTool` | 读取允许目录内的文本文件 |
| `search` | `WebSearchTool` | 返回模拟搜索结果，用于学习工具调用 |

### 添加新工具

1. 在 `com.agentloop.tools` 下创建一个 Spring `@Component`。
2. 使用 `@Tool` 标注模型可调用的方法。
3. 使用 `@ToolParam` 描述参数含义。
4. 在 `AgentOrchestrator` 的 `defaultTools(...)` 中注册工具实例。
5. 添加一个能够明确触发该工具的测试问题。

## 项目结构

```text
src/main
├── java/com/agentloop
│   ├── AgentApplication.java
│   ├── agent
│   │   ├── AgentContext.java
│   │   ├── AgentLoopState.java
│   │   ├── AgentMetrics.java
│   │   ├── AgentOrchestrator.java
│   │   ├── MdcFilter.java
│   │   └── ResilientToolExecutor.java
│   ├── config
│   │   ├── AgentProperties.java
│   │   └── ChatClientConfig.java
│   ├── controller
│   │   └── WebController.java
│   ├── memory
│   │   ├── ChatMemoryService.java
│   │   └── ChatSessionService.java
│   ├── service
│   │   └── AgentService.java
│   └── tools
│       ├── CurrentDateTool.java
│       ├── FileReadTool.java
│       ├── MathTool.java
│       └── WebSearchTool.java
└── resources
    ├── application.yml
    └── static
        └── index.html
```

推荐按以下顺序阅读：

1. `WebController.java`：了解浏览器请求如何进入应用
2. `AgentService.java`：了解一次执行上下文如何创建和保存
3. `AgentContext.java` 与 `AgentLoopState.java`：了解状态和步骤数据
4. `AgentOrchestrator.java`：阅读 Agent Loop 核心实现
5. `ResilientToolExecutor.java`：了解工具执行、重试与指标
6. `tools/`：学习 Tool Calling 的声明方式
7. `ChatMemoryService.java`：学习会话上下文持久化
8. `index.html`：了解学习实验室的前端实现

## 运行要求

- JDK 17 或更高版本
- Maven 3.9+
- MySQL 8.x
- 通义千问 DashScope API Key

## 启动项目

### 1. 配置 API Key

```bash
export AI_DASHSCOPE_API_KEY=your-api-key
```

可选设置模型温度：

```bash
export TEMPERATURE=0.1
```

复杂任务响应较慢时，可以调整 DashScope HTTP 读取超时，单位为秒：

```bash
export DASHSCOPE_READ_TIMEOUT=45
```

项目会关闭 Spring AI 内层重试，统一由 `AgentOrchestrator` 根据 `agent.llm-retries` 执行重试，避免两层重试导致请求时间和日志数量成倍增加。

### 2. 准备 MySQL

默认配置为：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/minimal-agent-loop
    username: root
    password: 123456
```

连接参数包含 `createDatabaseIfNotExist=true`。数据库账户拥有建库权限时，应用可以自动创建数据库；`chat_messages` 表会在应用启动时创建。

如本地配置不同，请修改 `src/main/resources/application.yml`，或通过 Spring Boot 支持的环境变量覆盖配置。

### 3. 编译并启动

```bash
mvn test
mvn spring-boot:run
```

应用默认运行在 `8085` 端口：

- 学习实验室：<http://localhost:8085/>
- 健康接口：<http://localhost:8085/api/health>
- Actuator 健康检查：<http://localhost:8085/actuator/health>
- Prometheus 指标：<http://localhost:8085/actuator/prometheus>

## API

### 发起 Agent 对话

```http
POST /api/chat
Content-Type: application/json

{
  "message": "帮我计算 12 * 8",
  "sessionId": "learning-session-01"
}
```

响应：

```json
{
  "response": "12 × 8 = 96"
}
```

### 流式 Agent 对话

```http
POST /api/chat/stream
Accept: text/event-stream
Content-Type: application/json

{
  "message": "解释 Agent Loop",
  "sessionId": "learning-session-01"
}
```

接口使用 SSE 按顺序发送以下事件：

| 事件 | 数据 | 说明 |
| --- | --- | --- |
| `ready` | `sessionId` | SSE 连接已建立 |
| `state` | `state`、`step` | Agent 当前状态与步骤 |
| `token` | `content` | 模型生成的增量文本 |
| `reset` | `reason` | 重试或工具调用后清除上一轮临时输出 |
| `done` | `response` | 完整回答，用于最终 Markdown 渲染 |
| `error` | `message` | 流式执行异常 |

前端默认使用该接口。原有 `/api/chat` 同步接口继续保留，方便命令行调用和兼容非流式客户端。

### 会话接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/sessions` | 获取会话摘要列表 |
| `GET` | `/api/sessions/{sessionId}/messages` | 获取最近 20 条会话消息 |
| `DELETE` | `/api/sessions?sessionId={id}` | 删除指定会话的消息 |
| `GET` | `/api/health` | 获取应用基础健康状态 |

## 主要配置

```yaml
agent:
  max-steps: 10
  timeout-seconds: 120
  step-timeout-ms: 30000
  llm-retries: 2
  tool-retries: 2
  file-read:
    base-dir: ${user.dir}
    max-size: 1048576
```

| 配置 | 说明 |
| --- | --- |
| `max-steps` | 单次 Agent Loop 最大推理步骤数 |
| `timeout-seconds` | 单次 Agent 执行总超时时间 |
| `step-timeout-ms` | 写入执行上下文的单步超时配置 |
| `llm-retries` | 单步 LLM 调用最大尝试次数 |
| `tool-retries` | 工具调用重试次数 |
| `file-read.base-dir` | `read_file` 允许访问的根目录 |
| `file-read.max-size` | 单个文件允许读取的最大字节数 |

DashScope 连接相关配置：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.ai.dashscope.read-timeout` | `45` | 等待模型返回的 HTTP 读取超时，单位为秒 |
| `spring.ai.retry.max-attempts` | `1` | 关闭 Spring AI 内层重试，避免与 Agent 重试叠加 |

## 可观测性

项目通过 `AgentMetrics` 记录 Agent Loop 和工具执行相关指标，并将指标暴露给 Prometheus。

同时，`MdcFilter` 会为 HTTP 请求写入日志追踪上下文，方便通过日志定位一次 Agent 执行。

```bash
curl http://localhost:8085/actuator/prometheus
```

## 当前边界

- `search` 是教学用模拟实现，没有接入真实搜索服务。
- Loop 观察器展示的是教学阶段动画，不是后端实时 trace。
- `step-timeout-ms` 当前保存在执行上下文中；主循环主要通过总超时兜底。
- 对话记忆依赖 MySQL，项目暂未提供纯内存运行模式。
- `ChatSessionService` 已包含独立会话表的基础 CRUD，但当前 Web API 使用的是 `ChatMemoryService` 根据消息聚合出的会话摘要。

## 后续学习路线

可以按照以下顺序继续扩展：

1. 通过 SSE 推送真实 Agent 步骤与工具执行事件
2. 在 Loop 观察器中展示工具名称、参数、耗时和返回结果
3. 增加主动停止执行与客户端断开后的任务取消功能
4. 增加人工确认节点（Human in the Loop）
5. 增加长期记忆、任务规划和多 Agent 协作

## 验证

```bash
mvn test
```

更多手动测试场景请查看 [`TEST_GUIDE.md`](TEST_GUIDE.md)。

# Minimal Agent Loop

一个用于学习 Spring AI Alibaba Function Calling、Agent Loop 和 RAG 的最小示例项目。

项目故意保留了较直接的分层方式：Controller 负责 HTTP，Service 负责对外门面，Orchestrator 负责 Agent 循环，Tools 负责模型可调用能力，RAG 模块负责文档入库和检索。

## 你可以学到什么

- 如何用 `ChatClient.defaultTools(...)` 给模型注册工具
- 如何组织一个简单的 Agent Loop：思考、调用工具、把工具结果放回上下文、继续生成
- 如何做一个基础 RAG 流程：文档解析、分块、向量入库、检索、带来源回答
- 如何给对话加会话记忆
- 如何用 Actuator 和 Micrometer 暴露 Agent 运行指标

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Web/API | Spring Boot 3.3 |
| LLM/Embedding | Spring AI Alibaba DashScope |
| Agent 工具调用 | Spring AI Tool Calling |
| 向量库 | Milvus |
| 元数据/记忆 | MySQL + JdbcTemplate |
| 指标 | Spring Boot Actuator + Micrometer + Prometheus |
| Java | 17+ |

## 运行前准备

当前配置使用 MySQL 和 Milvus，不是纯内存模式。

1. 设置通义千问 API Key：

```bash
export AI_DASHSCOPE_API_KEY=your-api-key
```

2. 准备 MySQL：

默认配置见 [application.yml](/src/main/resources/application.yml)：

```yaml
spring.datasource.url: jdbc:mysql://localhost:3306/minimal-agent-loop
spring.datasource.username: root
spring.datasource.password: 123456
```

3. 准备 Milvus：

```bash
docker run -d --name milvus-standalone -p 19530:19530 milvusdb/milvus:latest
```

如果你本地已有 Milvus，只要确认 `localhost:19530` 可访问即可。

## 启动

```bash
mvn spring-boot:run
```

默认端口是 `8085`：

- 聊天页：`http://localhost:8085/`
- 知识库页：`http://localhost:8085/knowledge`
- 健康检查：`http://localhost:8085/api/health`
- Prometheus 指标：`http://localhost:8085/actuator/prometheus`

命令行单次执行：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="今天几号？"
```

## 项目结构

```text
src/main/java/com/agentloop
├── AgentApplication.java          # Spring Boot 入口；支持交互模式和单次执行
├── controller/
│   └── WebController.java         # Web 页面跳转、聊天 API、知识库 API
├── service/
│   └── AgentService.java          # 对外执行门面，创建 AgentContext 并保存记忆
├── agent/
│   ├── AgentOrchestrator.java     # Agent Loop 核心状态机
│   ├── AgentContext.java          # 单次执行上下文
│   ├── AgentLoopState.java        # Loop 状态定义
│   ├── ResilientToolExecutor.java # 工具调用重试、限流和指标
│   ├── AgentMetrics.java          # Micrometer 指标
│   └── MdcFilter.java             # HTTP traceId/sessionId 日志上下文
├── tools/
│   ├── MathTool.java              # calculator
│   ├── CurrentDateTool.java       # get_date
│   ├── FileReadTool.java          # read_file
│   ├── WebSearchTool.java         # search，当前是 mock 实现
│   └── RagTool.java               # rag_query / rag_add_document
├── rag/
│   ├── DocumentParser.java        # 上传文件转文本
│   ├── DocumentChunker.java       # 文档分块
│   ├── DocumentRegistry.java      # 文档元数据 + 向量入库
│   ├── IndexingService.java       # 入库门面
│   └── RetrievalService.java      # 检索和带引用回答
├── memory/
│   ├── ChatMemoryService.java     # 对话消息记忆
│   └── ChatSessionService.java    # 会话元数据服务，当前未暴露 API
└── config/
    ├── AgentProperties.java       # agent.* 配置绑定
    ├── ChatClientConfig.java
    └── MilvusVectorStoreConfig.java
```

## 请求链路

一次聊天请求的大致流程：

```text
WebController /api/chat
  -> AgentService.execute(...)
    -> 创建 AgentContext
    -> AgentOrchestrator.execute(ctx)
      -> 读取最近会话记忆
      -> 如果启用知识库，先尝试 RAG 上下文增强
      -> 调用 LLM
      -> 如果 LLM 请求工具，执行工具并把结果放回上下文
      -> 重复直到模型直接回答、超时或达到最大步数
    -> 保存 user/assistant 消息到 MySQL
```

## 主要配置

```yaml
agent:
  max-steps: 10              # 单次 Agent Loop 最大步数
  timeout-seconds: 120       # 单次请求总超时
  step-timeout-ms: 30000     # 单步超时配置，写入 AgentContext
  llm-retries: 2             # LLM 调用重试次数
  tool-retries: 2            # 工具调用重试次数
  file-read:
    base-dir: ${user.dir}    # read_file 允许读取的根目录
    max-size: 1048576        # 单文件最大读取大小
```

## 工具说明

| 工具名 | 类 | 说明 |
| --- | --- | --- |
| `calculator` | `MathTool` | 安全表达式计算 |
| `get_date` | `CurrentDateTool` | 当前时间 |
| `read_file` | `FileReadTool` | 读取 base-dir 内文本文件，带路径逃逸保护 |
| `search` | `WebSearchTool` | Mock 搜索结果，适合教学占位 |
| `rag_query` | `RagTool` | 查询知识库 |
| `rag_add_document` | `RagTool` | 添加单条文档 |
| `rag_add_documents` | `RagTool` | 批量添加文档 |

添加新工具的步骤：

1. 在 `com.agentloop.tools` 下创建 `@Component` 类。
2. 给方法加 `@Tool(name = "...", description = "...")`。
3. 参数用 `@ToolParam` 描述清楚。
4. 在 `AgentOrchestrator` 的 `defaultTools(...)` 中注册。

## RAG 数据流

```text
上传/添加文档
  -> DocumentParser.parse(...)
  -> DocumentChunker.chunk(...)
  -> VectorStore.add(...) 写入 Milvus
  -> rag_documents 写入 MySQL

用户提问
  -> RetrievalService.retrieve(...)
  -> Milvus similaritySearch
  -> RetrievalService.answerWithCitations(...)
```

MySQL 是知识库元数据和文档清单，Milvus 是向量检索数据。清空知识库时会先按 MySQL 中的 chunk id 删除 Milvus 向量，再删除 MySQL 元数据。

## 学习时建议关注的代码

推荐按这个顺序读：

1. [AgentApplication.java](/src/main/java/com/agentloop/AgentApplication.java)
2. [WebController.java](/src/main/java/com/agentloop/controller/WebController.java)
3. [AgentService.java](/src/main/java/com/agentloop/service/AgentService.java)
4. [AgentOrchestrator.java](/src/main/java/com/agentloop/agent/AgentOrchestrator.java)
5. [RagTool.java](/src/main/java/com/agentloop/tools/RagTool.java)
6. [DocumentRegistry.java](/src/main/java/com/agentloop/rag/DocumentRegistry.java)
7. [RetrievalService.java](/src/main/java/com/agentloop/rag/RetrievalService.java)

## 已知简化

- `WebSearchTool` 当前只是 mock，没有接真实搜索 API。
- PDF 解析是极简实现，只适合未压缩 ASCII PDF；生产场景建议接 PDFBox。
- `ChatSessionService` 已有基础 CRUD，但当前 WebController 没有暴露会话管理 API。
- `step-timeout-ms` 目前写入上下文，主循环按总超时兜底；如果要严格限制每一步，需要给 LLM 和工具执行再加独立超时控制。

## 验证

```bash
mvn -q -DskipTests compile
```

更多手动测试用例见 [TEST_GUIDE.md](/TEST_GUIDE.md)。

# Minimal Agent Loop

基于 Spring AI Alibaba 的轻量级 Agent 框架演示项目。通过 Function Calling 工具调用实现交互式 AI Agent，支持网页搜索、数学计算、文件读取、时间查询、RAG 知识库检索等工具。

**开发者：JoeyLiu**

## 项目概述

本项目是一个最小化的 Agent 执行引擎，演示了如何基于 Spring AI Alibaba 构建一个支持多工具调用的 AI Agent 应用。

核心特性：
- **Function Calling**：模型自动识别并调用合适的工具
- **工具扩展**：通过 `@Tool` 注解轻松注册新工具
- **RAG 支持**：内置知识库检索增强生成（chunk → embed → retrieve → answer with citations）
- **会话记忆**：支持短期会话记忆和长期记忆扩展
- **交互模式**：支持命令行交互和单次执行两种模式
- **超时控制**：可配置最大执行步骤和超时时间
- **日志追踪**：完整的执行日志便于调试

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | 语言版本 |
| Spring Boot | 3.3.0 | 基础框架 |
| Spring AI Alibaba | 1.1.2.2 | AI 模型集成 |
| Spring AI Alibaba Agent Framework | 1.1.2.2 | Agent 任务编排 |
| Spring AI Vector Store | - | RAG 向量存储（SimpleVectorStore / Milvus） |
| Lombok | - | 简化代码 |

## 项目结构

```
minimal-agent-loop/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/agentloop/
    │   ├── AgentApplication.java         # Spring Boot 入口，支持交互/单次模式
    │   ├── config/
    │   │   └── AgentProperties.java     # Agent 配置属性
    │   ├── service/
    │   │   └── AgentService.java        # 核心执行引擎，运行 Agent 循环
    │   ├── memory/
    │   │   └── ChatMemoryService.java   # 会话记忆服务
    │   ├── rag/
    │   │   ├── DocumentChunker.java     # 文档分块
    │   │   ├── EmbeddingService.java    # 向量嵌入（text-embedding-v3）
    │   │   ├── IndexingService.java     # 文档索引
    │   │   ├── RetrievalService.java    # 检索 + 带引用的回答
    │   │   └── VectorStoreConfig.java  # 向量存储配置（SimpleVectorStore / Milvus）
    │   └── tools/
    │       ├── WebSearchTool.java       # 网页搜索工具
    │       ├── MathTool.java            # 数学计算工具
    │       ├── FileReadTool.java        # 文件读取工具
    │       ├── CurrentDateTool.java     # 当前日期时间工具
    │       └── RagTool.java             # RAG 知识库工具
    └── resources/
        └── application.yml              # 应用配置
```

## 快速开始

### 1. 配置环境变量

启动前必须设置以下环境变量：

```bash
# 通义千问 API Key（必填）
export AI_DASHSCOPE_API_KEY=your-api-key-here

# 可选：切换模型
export MODEL=qwen-plus
export TEMPERATURE=0.7
```

### 2. 编译运行

```bash
mvn clean compile
mvn spring-boot:run
```

### 3. 使用方式

**交互模式**（启动后直接对话）：

```
Agent Loop — interactive mode. Type 'exit' to quit.

You: 2+2等于几？
Agent: 2+2等于4。

You: 今天几号？
Agent: 今天是2025年5月29日。

You: 把"Spring AI 是一个 AI 集成框架"加入知识库
Agent: 已将文档添加到知识库。

You: 什么是 Spring AI？
Agent: [根据知识库检索回答，并附带来源引用]
```

**单次执行**：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="你好，今天是几号？"
```

## RAG 知识库

内置检索增强生成（RAG）功能，支持向知识库添加文档并基于文档内容回答问题。

### RagTool 工具

| 工具 | 说明 | 参数 |
|------|------|------|
| `rag_query` | 查询知识库 | `query` — 搜索问题 |
| `rag_add_document` | 添加文档 | `content` — 文档内容，`source` — 来源/标题 |
| `rag_add_documents` | 批量添加 | `contents` — 文档列表，`sources` — 来源列表 |

### 向量存储配置

默认使用内存 SimpleVectorStore，无需额外配置。如需切换到 Milvus：

1. 启动 Milvus：`docker run -d -p 19530:19530 milvusdb/milvus:latest`
2. 修改 `VectorStoreConfig.java`，注释 SimpleVectorStore，取消注释 Milvus 配置
3. 设置环境变量：`MILVUS_HOST`, `MILVUS_PORT`, `MILVUS_COLLECTION`

## 核心组件说明

### AgentService

核心执行引擎，负责：
- 初始化 ChatClient 并注册工具
- 运行 Agent 循环，最大执行 `max-steps` 步
- 处理超时（默认 120 秒）和异常
- 返回模型最终响应

### 工具注册流程

1. 在 `tools/` 包下创建类，标注 `@Component`
2. 方法标注 `@Tool`，提供 `name` 和 `description`
3. 参数标注 `@ToolParam`，描述参数用途
4. 在 `AgentService` 构造函数中注册到 `ChatClient.defaultTools()`

示例：

```java
@Component
public class MathTool {
    @Tool(name = "calculator", description = "Calculate a mathematical expression")
    public String calculate(@ToolParam(description = "数学表达式，如 2+3*5") String expr) {
        // 实现逻辑
    }
}
```

## 配置说明

| 配置项 | 环境变量 | 说明 |
|--------|----------|------|
| `spring.ai.dashscope.api-key` | `AI_DASHSCOPE_API_KEY` | 通义千问 API Key（必填） |
| `spring.ai.dashscope.chat.options.model` | `MODEL` | 模型名称，默认 qwen-plus |
| `spring.ai.dashscope.chat.options.temperature` | `TEMPERATURE` | 生成温度参数，默认 0.7 |
| `spring.ai.dashscope.embedding.options.model` | `EMBEDDING_MODEL` | 嵌入模型，默认 text-embedding-v3 |
| `agent.max-steps` | — | Agent 最大执行步数，默认 10 |
| `agent.timeout-seconds` | — | 单次执行超时时间（秒），默认 120 |

## 扩展指南

### 添加新工具

1. 在 `com.agentloop.tools` 包下创建新的 Tool 类
2. 参考现有工具实现 `@Tool` 注解的方法
3. 在 `AgentService` 构造函数中添加注入和注册

### 切换模型

修改 `application.yml` 中的 `model` 配置：

```yaml
spring:
  ai:
    dashscope:
      chat:
        options:
          model: qwen-plus   # 切换为更强模型
```

### 启用 Milvus 向量存储

1. 安装 Docker 并启动 Milvus
2. 修改 `VectorStoreConfig.java` 中的配置
3. 添加 Milvus SDK 依赖到 pom.xml

## 常见问题

**Q: 运行时提示 `api-key` 错误？**
A: 检查环境变量 `AI_DASHSCOPE_API_KEY` 是否正确设置。

**Q: RAG 检索返回空结果？**
A: 知识库可能为空，先使用 `rag_add_document` 添加文档。

**Q: 如何增加工具调用的最大次数？**
A: 在 `application.yml` 中修改 `agent.max-steps: 20`。
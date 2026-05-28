# Minimal Agent Loop

基于 Spring AI Alibaba 的轻量级 Agent 框架演示项目。通过 Function Calling 工具调用实现交互式 AI Agent，支持网页搜索、数学计算、文件读取、时间查询等工具。

**开发者：JoeyLiu**

## 项目概述

本项目是一个最小化的 Agent 执行引擎，演示了如何基于 Spring AI Alibaba 构建一个支持多工具调用的 AI Agent 应用。

核心特性：
- **Function Calling**：模型自动识别并调用合适的工具
- **工具扩展**：通过 `@Tool` 注解轻松注册新工具
- **交互模式**：支持命令行交互和单次执行两种模式
- **超时控制**：可配置最大执行步骤和超时时间
- **日志追踪**：完整的执行日志便于调试
- **MiniMax MCP**：支持通过 MCP 协议接入 MiniMax Token Plan 网络搜索和图片理解工具

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | 语言版本 |
| Spring Boot | 3.3.0 | 基础框架 |
| Spring AI Alibaba | 1.1.2.2 | AI 模型集成 |
| Spring AI Alibaba Agent Framework | 1.1.2.2 | Agent 任务编排 |
| Spring AI MCP Client | 1.1.2 | MCP 协议支持（MiniMax MCP 接入） |
| Lombok | - | 简化代码 |
| MySQL | 8.0+ | 对话历史存储（可选） |

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
    │   ├── mcp/
    │   │   ├── MiniMaxMcpConfig.java    # MiniMax MCP 配置（stdio 子进程）
    │   │   └── McpDemoController.java   # MCP 工具演示端点
    │   ├── service/
    │   │   └── AgentService.java        # 核心执行引擎，运行 Agent 循环
    │   └── tools/
    │       ├── WebSearchTool.java       # 网页搜索工具（mock）
    │       ├── MathTool.java            # 数学计算工具
    │       ├── FileReadTool.java        # 文件读取工具
    │       └── CurrentDateTool.java     # 当前日期时间工具
    └── resources/
        ├── application.yml              # 应用配置
        └── mcp-servers.json            # MCP 服务配置
```

## 快速开始

### 1. 配置环境变量

启动前必须设置以下环境变量：

```bash
# 通义千问 API Key（必填）
export AI_DASHSCOPE_API_KEY=your-api-key-here

# MiniMax Token Plan API Key（必填，用于 MCP 工具）
export MINIMAX_API_KEY=your-token-plan-key

# MiniMax MCP 本地输出目录（需保证路径存在且可写）
export MINIMAX_MCP_BASE_PATH=/Users/lss/workspace/workspace-ai
```

**Token Plan Key 获取方式**：访问 [MiniMax 开放平台 > 订阅管理 > Token Plan](https://platform.minimaxi.com) 查看。

### 2. 编译运行

```bash
mvn clean compile
mvn spring-boot:run
```

### 3. 使用方式

**交互模式**（启动后直接对话）：

```
Agent Loop — interactive mode. Type 'exit' to quit.

You: 帮我搜索一下今天有什么新闻
Agent: [搜索结果...]
```

**单次执行**：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="你好，今天是几号？"
```

## MiniMax MCP 使用指南

本项目通过 Spring AI MCP Client 以 stdio 模式接入 MiniMax Token Plan MCP，提供 `web_search`（网络搜索）和 `understand_image`（图片理解）两个工具。

### MCP 端点演示

启动应用后可直接通过 HTTP 调用 MCP 工具：

**1. 网络搜索**

```
GET /mcp/demo/search?query=今天天气
```

```json
{
  "tool": "web_search",
  "query": "今天天气",
  "result": "今天全国大部分地区晴朗..."
}
```

**2. 图片理解**

```
GET /mcp/demo/image?imageUrl=https://example.com/photo.png&prompt=这张图片里有什么
```

```json
{
  "tool": "understand_image",
  "imageUrl": "https://example.com/photo.png",
  "prompt": "这张图片里有什么",
  "result": "图片中显示的是..."
}
```

**3. 查看帮助**

```
GET /mcp/demo/help
```

### MCP 工具说明

| 工具 | 说明 | 参数 |
|------|------|------|
| `web_search` | 网络搜索 | `query` — 搜索关键词 |
| `understand_image` | 图片理解 | `prompt` — 分析要求，`image_url` — 图片地址（HTTP URL 或本地文件路径） |

**支持格式**：JPEG、PNG、GIF、WebP（最大 20MB）

**图片来源**：
- HTTP/HTTPS URL：`imageUrl=https://example.com/image.png`
- 本地文件：`imageUrl=file:///Users/lss/workspace/workspace-ai/screenshot.png`

### MCP 配置原理

`mcp-servers.json` 定义了 MCP 服务启动参数：

```json
{
  "servers": {
    "minimax": {
      "command": "uvx",
      "args": ["minimax-coding-plan-mcp", "-y"],
      "env": {
        "MINIMAX_API_KEY": "${MINIMAX_API_KEY}",
        "MINIMAX_API_HOST": "https://api.minimaxi.com",
        "MINIMAX_MCP_BASE_PATH": "${MINIMAX_MCP_BASE_PATH}"
      }
    }
  }
}
```

Spring AI MCP Client 启动时会通过 `uvx` 自动下载并运行 `minimax-coding-plan-mcp` 包，建立 stdio 通信并自动发现工具。

## 核心组件说明

### AgentService

核心执行引擎，负责：
- 初始化 ChatClient 并注册工具（MCP ToolCallbacks + 本地 @Tool）
- 运行 Agent 循环，最大执行 `max-steps` 步
- 处理超时（默认 120 秒）和异常
- 返回模型最终响应

### 工具注册流程

**本地工具（@Tool 注解）**：

1. 在 `tools/` 包下创建类，标注 `@Component`
2. 方法标注 `@Tool`，提供 `name` 和 `description`
3. 参数标注 `@ToolParam`，描述参数用途
4. 在 `AgentService` 构造函数中注册到 `ChatClient.defaultTools()`

**MCP 工具**：
- 由 Spring AI MCP Client 通过 `mcp-servers.json` 自动发现
- 注册到 `ChatClient.defaultToolCallbacks()`
- 无需手动注册，stdio 子进程启动后自动可用

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
| `spring.ai.dashscope.chat.options.model` | — | 模型名称，默认 qwen-turbo |
| `spring.ai.dashscope.chat.options.temperature` | — | 生成温度参数 |
| `spring.ai.mcp.client.stdio.servers-configuration` | — | MCP 服务配置文件路径 |
| `spring.ai.mcp.client.toolcallback.enabled` | — | 是否启用 MCP 工具回调，默认 true |
| `MINIMAX_API_KEY` | — | MiniMax Token Plan Key（必填） |
| `MINIMAX_API_HOST` | — | API 端点，默认 https://api.minimaxi.com |
| `MINIMAX_MCP_BASE_PATH` | — | 本地输出目录（需存在且可写） |
| `agent.max-steps` | — | Agent 最大执行步数，默认 10 |
| `agent.timeout-seconds` | — | 单次执行超时时间（秒），默认 120 |

## 依赖关系

```
┌─────────────────────────────────────────────┐
│           AgentApplication                 │
│  (Spring Boot 入口，CommandLineRunner)      │
└─────────────────┬─────────────────────────┘
                  │ 注入
┌─────────────────▼─────────────────────────┐
│            AgentService                   │
│  - ChatClient (通义千问)                   │
│  - 本地 @Tool (WebSearch/Math/...)        │
│  - MCP ToolCallbacks (MiniMax MCP)          │
│  - AgentProperties (配置)                 │
└─────────────────────────────────────────────┘
```

## 扩展指南

### 添加新本地工具

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

### 启用 MySQL 对话记忆

1. 创建数据库 `minimal_agent`
2. 在 `pom.xml` 中取消注释 MySQL 依赖
3. 在 `application.yml` 中取消注释 datasource 配置
4. 确保 MySQL 服务运行

## 常见问题

**Q: 运行时提示 `api-key` 错误？**
A: 检查环境变量 `AI_DASHSCOPE_API_KEY` 和 `MINIMAX_API_KEY` 是否正确设置。

**Q: MCP 工具（如 web_search）无法调用？**
A: 检查 `MINIMAX_API_KEY` 是否有效（需拥有 Token Plan 额度或积分权限）。

**Q: understand_image 报 "file not found"？**
A: 本地文件需使用绝对路径，如 `file:///Users/lss/workspace/workspace-ai/screenshot.png`。

**Q: 如何增加工具调用的最大次数？**
A: 在 `application.yml` 中修改 `agent.max-steps: 20`。

**Q: uvx 命令找不到？**
A: 安装 uv：`curl -LsSf https://astral.sh/uv/install.sh | sh`，安装后重新打开终端。
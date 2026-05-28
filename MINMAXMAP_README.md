# MiniMax MCP 使用指南

本文档说明如何在 minimal-agent-loop 项目中接入并使用 MiniMax Token Plan MCP（Model Context Protocol）。

> MCP 提供了两个专属工具：**网络搜索**（web_search）和 **图片理解**（understand_image）。

---

## 目录

- [工作原理](#工作原理)
- [环境准备](#环境准备)
- [配置说明](#配置说明)
- [项目文件说明](#项目文件说明)
- [启动方式](#启动方式)
- [使用示例](#使用示例)
- [接口说明](#接口说明)
- [原理深入](#原理深入)
- [常见问题](#常见问题)

---

## 工作原理

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                 │
│                                                             │
│   AgentService                                              │
│   ├── ChatClient (通义千问)                                  │
│   ├── @Tool 本地工具 (WebSearchTool / MathTool / ...)       │
│   └── ToolCallbackProvider (MCP 工具)                      │
│                        │                                    │
│                        │ ChatClient.defaultToolCallbacks() │
│                        ▼                                    │
│   ┌────────────────────────────────────────────┐            │
│   │  spring-ai-starter-mcp-client               │            │
│   │  (MCP Client, stdio 模式)                  │            │
│   └──────────────────────┬─────────────────────┘            │
│                          │ stdio (标准输入/输出)              │
└──────────────────────────┼──────────────────────────────────┘
                           ▼
              ┌─────────────────────────┐
              │  uvx subprocess         │
              │  minimax-coding-plan-mcp │
              │  (Python, 自动下载安装)  │
              └─────────────────────────┘
                           │
                           ▼
              ┌─────────────────────────┐
              │  MiniMax Token Plan API  │
              │  web_search             │
              │  understand_image      │
              └─────────────────────────┘
```

**流程说明：**

1. Spring Boot 启动时，`spring-ai-starter-mcp-client` 根据 `mcp-servers.json` 配置通过 `uvx` 拉起 `minimax-coding-plan-mcp` 子进程
2. 子进程通过 **stdio**（标准输入/输出）与 Spring 应用通信
3. MCP Server 启动后向 ChatClient 通告自身支持的工具列表（web_search、understand_image）
4. 用户提问时，模型判断需要调用 MCP 工具，`spring-ai-starter-mcp-client` 将调用请求转发给子进程
5. 子进程调用 MiniMax API，将结果返回给 Spring 应用

---

## 环境准备

### 1. 安装 uvx

`uvx` 是 [uv](https://github.com/astral-sh/uv)（Python 包管理器）的配套工具，用于直接运行远程 Python 包。

```bash
# macOS / Linux
curl -LsSf https://astral.sh/uv/install.sh | sh

# 验证安装
which uvx
# 输出如 /usr/local/bin/uvx 即为成功
```

### 2. 获取 MiniMax Token Plan Key

访问 [MiniMax 开放平台 > 订阅管理 > Token Plan](https://platform.minimaxi.com) 查看您的 Token Plan Key。

> **注意**：该 Key 需要拥有 Token Plan 额度或积分权限后才能使用付费资源。

### 3. 确保 MCP 本地输出目录存在

`MINIMAX_MCP_BASE_PATH` 指定的目录必须存在且有写入权限：

```bash
mkdir -p ~/workspace/workspace-ai
```

---

## 配置说明

### 环境变量（启动前必须设置）

| 环境变量 | 必填 | 说明 |
|----------|------|------|
| `AI_DASHSCOPE_API_KEY` | ✅ | 通义千问 API Key |
| `MINIMAX_API_KEY` | ✅ | MiniMax Token Plan Key |
| `MINIMAX_MCP_BASE_PATH` | ✅ | 本地输出目录（需存在且可写） |
| `MINIMAX_API_HOST` | ❌ | API 端点，默认 `https://api.minimaxi.com` |

```bash
export AI_DASHSCOPE_API_KEY=your-dashscope-key
export MINIMAX_API_KEY=your-minimax-token-plan-key
export MINIMAX_MCP_BASE_PATH=~/workspace/workspace-ai
```

### application.yml（无需修改，已配置好）

```yaml
spring:
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}

    # MCP Client 配置（stdio 模式）
    mcp:
      client:
        stdio:
          servers-configuration: classpath:/mcp-servers.json
        toolcallback:
          enabled: true
```

`spring.ai.mcp.client.stdio.servers-configuration` 指向 `src/main/resources/mcp-servers.json`。

### mcp-servers.json（MCP 服务定义）

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

- `command: uvx` — 使用 uvx 运行远程包
- `args: ["minimax-coding-plan-mcp", "-y"]` — 自动安装并运行 MiniMax MCP
- `env` — 注入环境变量，MCP Client 会将这些变量传递给子进程

---

## 项目文件说明

```
minimal-agent-loop/
├── pom.xml                                          # Maven 配置
│   └── spring-ai-starter-mcp-client                # MCP Client 依赖（stdio + ToolCallback）
│
├── src/main/resources/
│   ├── application.yml                              # Spring Boot 配置（AI + MCP）
│   │   └── spring.ai.mcp.client.stdio.servers-configuration
│   └── mcp-servers.json                            # MCP 服务定义（stdio 子进程启动参数）
│
└── src/main/java/com/agentloop/
    ├── AgentApplication.java                        # Spring Boot 入口
    │
    ├── mcp/
    │   ├── MiniMaxMcpConfig.java                   # MCP 配置类
    │   │   作用：确保 MCP Client 在 Spring 上下文中正确初始化
    │   │   原理：stdio 子进程由 spring-ai-starter-mcp-client 自动启动，无需手动启动
    │   │
    │   └── McpDemoController.java                  # MCP 演示 Controller
    │       作用：提供 HTTP 端点演示 web_search 和 understand_image 工具
    │       路径：/mcp/demo/search 、/mcp/demo/image 、/mcp/demo/help
    │
    ├── service/
    │   └── AgentService.java                       # 核心执行引擎
    │       作用：注册 MCP ToolCallbackProvider 到 ChatClient，使模型能调用 MCP 工具
    │       关键代码：.defaultToolCallbacks(mcpTools)
    │
    └── tools/                                       # 本地工具（@Tool 注解）
        ├── WebSearchTool.java
        ├── MathTool.java
        ├── FileReadTool.java
        └── CurrentDateTool.java
```

### 各文件详细说明

#### pom.xml

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

`spring-ai-starter-mcp-client` 内部包含：
- MCP Client 核心实现（支持 stdio 和 SSE 两种传输模式）
- `McpToolCallbackProvider` — 将 MCP 工具转换为 Spring AI `ToolCallback`
- JSON 配置文件解析器

#### mcp-servers.json

定义了如何启动 MCP 服务（MCP Server）。当前配置使用 `uvx` 以 stdio 模式启动 `minimax-coding-plan-mcp`。

Spring AI MCP Client 解析此文件后，会 fork 一个子进程，通过 stdin/stdout 与子进程通信。

#### MiniMaxMcpConfig.java

```java
@Configuration
@Slf4j
public class MiniMaxMcpConfig {

    public MiniMaxMcpConfig() {
        log.info("MiniMax MCP config initialized (stdio subprocess auto-starts on first use)");
    }
}
```

占位配置类，实际 MCP Client 由 `spring-ai-starter-mcp-client` 自动初始化。

#### McpDemoController.java

提供三个 HTTP GET 端点：

| 端点 | 方法 | 参数 | 说明 |
|------|------|------|------|
| `/mcp/demo/search` | GET | `query` | 调用 `web_search` 搜索工具 |
| `/mcp/demo/image` | GET | `imageUrl`, `prompt`（可选） | 调用 `understand_image` 图片理解工具 |
| `/mcp/demo/help` | GET | 无 | 显示帮助信息 |

#### AgentService.java（与 MCP 相关部分）

```java
public AgentService(
        ChatClient.Builder chatClientBuilder,
        ...
        ToolCallbackProvider mcpTools,  // MCP 工具提供者（自动注入）
        AgentProperties properties
) {
    this.chatClient = chatClientBuilder
            ...
            .defaultToolCallbacks(mcpTools)  // 注册到 ChatClient
            .build();
}
```

`ToolCallbackProvider mcpTools` 由 Spring AI MCP Client 自动注入，注册到 ChatClient 后模型即可调用 MCP 工具。

---

## 启动方式

### 方式一：交互模式

```bash
export AI_DASHSCOPE_API_KEY=your-dashscope-key
export MINIMAX_API_KEY=your-minimax-token-plan-key
export MINIMAX_MCP_BASE_PATH=~/workspace/workspace-ai

cd /Users/lss/workspace/workspace-ai/minimal-agent-loop
mvn spring-boot:run
```

启动后可直接对话，模型会自动调用 MCP 工具：

```
You: 帮我搜索一下今天有什么新闻
Agent: [调用 web_search 工具后返回搜索结果]

You: 分析一下这张图片 https://example.com/photo.png
Agent: [调用 understand_image 工具后返回图片分析结果]
```

### 方式二：HTTP 端点调用

启动应用后，使用浏览器或 curl 调用：

```bash
# 网络搜索
curl "http://localhost:8080/mcp/demo/search?query=今天天气"

# 图片理解
curl "http://localhost:8080/mcp/demo/image?imageUrl=https://example.com/photo.png&prompt=这张图片里有什么"

# 帮助信息
curl "http://localhost:8080/mcp/demo/help"
```

### 方式三：单次执行

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="帮我搜索一下今天有什么新闻"
```

---

## 使用示例

### 示例 1：网络搜索（交互模式）

```
You: 搜索一下今天有什么新闻
Agent: 我来帮你搜索今天的新闻。
[调用 web_search] 搜索关键词："今天新闻"
[web_search 结果] 今日要闻：...（模型基于搜索结果回复）
```

### 示例 2：图片理解（HTTP 端点）

```
GET /mcp/demo/image?imageUrl=https://example.com/photo.png&prompt=这张图片里有什么
```

响应：

```json
{
  "tool": "understand_image",
  "imageUrl": "https://example.com/photo.png",
  "prompt": "这张图片里有什么",
  "result": "图片中显示的是一只猫在草地上休息..."
}
```

### 示例 3：本地文件图片理解

```
GET /mcp/demo/image?imageUrl=file:///Users/lss/workspace/workspace-ai/screenshot.png&prompt=描述这张截图的内容
```

> 本地文件需使用 `file://` 前缀 + 绝对路径。

---

## 接口说明

### MCP 工具列表

| 工具名称 | 说明 | 输入参数 |
|----------|------|----------|
| `web_search` | 对关键词进行网络搜索 | `query`（string）：搜索关键词 |
| `understand_image` | 对图片进行理解和分析 | `prompt`（string）：分析要求；`image_url`（string）：图片地址 |

### understand_image 支持的图片来源

| 类型 | 格式 | 示例 |
|------|------|------|
| HTTP/HTTPS URL | — | `https://example.com/image.png` |
| 本地文件 | 绝对路径 + `file://` 前缀 | `file:///Users/lss/workspace/workspace-ai/screenshot.png` |

### HTTP 端点接口

#### GET /mcp/demo/search

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ✅ | 搜索关键词 |

**响应示例：**

```json
{
  "tool": "web_search",
  "query": "今天天气",
  "result": "今天全国大部分地区晴朗，局部有雨..."
}
```

**错误响应：**

```json
{
  "tool": "web_search",
  "query": "今天天气",
  "error": "Failed to call MCP tool: ..."
}
```

#### GET /mcp/demo/image

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `imageUrl` | string | ✅ | 图片地址（HTTP URL 或本地文件 `file://` 路径） |
| `prompt` | string | ❌ | 分析要求，默认"描述这张图片的内容" |

**响应示例：**

```json
{
  "tool": "understand_image",
  "imageUrl": "https://example.com/photo.png",
  "prompt": "描述这张图片的内容",
  "result": "图片中显示的是一只猫在草地上休息..."
}
```

#### GET /mcp/demo/help

**响应示例：**

```json
{
  "description": "MiniMax Token Plan MCP 演示端点",
  "endpoints": {
    "/mcp/demo/search": {...},
    "/mcp/demo/image": {...},
    "/mcp/demo/help": {...}
  },
  "mcp_tools": {
    "web_search": "对关键词进行网络搜索，返回搜索结果摘要",
    "understand_image": "对图片进行理解和分析..."
  }
}
```

---

## 原理深入

### stdio 模式工作流程

1. **启动阶段**：Spring AI MCP Client 解析 `mcp-servers.json`，使用 `ProcessBuilder` fork `uvx minimax-coding-plan-mcp -y` 子进程
2. **握手阶段**：子进程启动后通过 stdout 发送 `initialize` 请求，MCP Client 回复 `initialize` 响应
3. **工具发现**：子进程发送 `tool/list` 响应，通告 `web_search` 和 `understand_image` 两个工具
4. **调用阶段**：用户提问 → 模型判断需调用工具 → MCP Client 通过 stdin 发送 `tool/call` 请求 → 子进程调用 MiniMax API → 结果通过 stdout 返回
5. **结果处理**：MCP Client 解析响应，转换为 Spring AI `FunctionCall` 格式，追加到模型输入中

### 与 Cursor / Claude Code 的区别

| 平台 | 集成方式 | 说明 |
|------|----------|------|
| Cursor | `.cursor/mcp.json` 配置 | MCP Server 作为 IDE 插件运行 |
| Claude Code | `claude mcp add` 命令 | MCP Server 作为 CLI 子进程运行 |
| **minimal-agent-loop** | Spring AI MCP Client + `mcp-servers.json` | MCP Server 作为 Java 子进程运行，工具注册到 ChatClient |

核心原理相同：都是通过 stdio 通信，启动 `uvx minimax-coding-plan-mcp -y` 子进程，只是集成载体不同（IDE / CLI / Spring Boot）。

---

## 常见问题

### Q: 提示 `uvx: command not found`

需要安装 uv：

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

安装后**重新打开终端**，使 PATH 更新。

### Q: MCP 工具（如 web_search）无法调用，提示权限错误

检查 `MINIMAX_API_KEY` 是否有效，需拥有 Token Plan 额度或积分权限才能使用。

### Q: understand_image 报 "file not found"

本地文件必须使用绝对路径和 `file://` 前缀：

```
✅ file:///Users/lss/workspace/workspace-ai/screenshot.png
❌ ~/workspace/workspace-ai/screenshot.png
❌ /Users/lss/workspace/workspace-ai/screenshot.png
```

### Q: 第一次调用很慢

正常现象。MCP Client 会在**第一次使用时**自动通过 `uvx` 下载并安装 `minimax-coding-plan-mcp` 包（约 10-30 秒）。之后调用直接从本地缓存加载，速度正常。

### Q: 如何确认 MCP 是否连接成功？

启动应用后查看日志，若看到以下日志说明 MCP Client 初始化正常：

```
MiniMax MCP config initialized (stdio subprocess auto-starts on first use)
```

若调用 MCP 工具时报错，日志会显示具体原因（如 API Key 无效、超时等）。

### Q: 可以同时使用多个 MCP 服务吗？

可以。在 `mcp-servers.json` 的 `servers` 中添加多个服务定义即可，例如：

```json
{
  "servers": {
    "minimax": { ... },
    "super-sql": {
      "command": "uvx",
      "args": ["super-sql-mcp", "-y"],
      "env": { ... }
    }
  }
}
```

### Q: 如何调试 MCP 通信？

可在 `application.yml` 中开启详细日志：

```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    org.springframework.ai.tool: DEBUG
```

重启应用后，stderr 会显示 MCP 协议通信详情（JSON-RPC 请求/响应）。

---

## 相关资源

- [MiniMax Token Plan MCP 官方文档](https://platform.minimaxi.com/docs/guides/token-plan-mcp-guide)
- [uv 安装](https://github.com/astral-sh/uv)
- [Spring AI MCP Client 文档](https://docs.spring.io/spring-ai/reference/mcp/mcp-client.html)
- [GitHub 仓库](https://github.com/joeyliu1/minimal-agent-loop)
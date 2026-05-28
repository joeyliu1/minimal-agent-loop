# Minimal Agent Loop

基于 Spring AI Alibaba 的轻量级 Agent 框架演示项目。通过 Function Calling 工具调用实现交互式 AI Agent，支持网页搜索、数学计算、文件读取、时间查询等工具。

## 项目概述

本项目是一个最小化的 Agent 执行引擎，演示了如何基于 Spring AI Alibaba 构建一个支持多工具调用的 AI Agent 应用。

核心特性：
- **Function Calling**：模型自动识别并调用合适的工具
- **工具扩展**：通过 `@Tool` 注解轻松注册新工具
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
| Lombok | - | 简化代码 |
| MySQL | 8.0+ | 对话历史存储（可选） |

## 项目结构

```
minimal-agent-loop/
├── pom.xml
└── src/main/java/com/agentloop/
    ├── AgentApplication.java         # Spring Boot 入口，支持交互/单次模式
    ├── config/
    │   └── AgentProperties.java     # Agent 配置属性（max-steps, timeout-seconds）
    ├── service/
    │   └── AgentService.java        # 核心执行引擎，运行 Agent 循环
    └── tools/
        ├── WebSearchTool.java       # 网页搜索工具（mock 实现）
        ├── MathTool.java            # 数学计算工具
        ├── FileReadTool.java        # 文件读取工具
        └── CurrentDateTool.java     # 当前日期时间工具
```

## 快速开始

### 1. 配置 API Key

在 `src/main/resources/application.yml` 中设置通义千问 API Key：

```yaml
spring:
  ai:
    dashscope:
      api-key: your-api-key-here   # 替换为你的 API Key
```

或通过环境变量设置：

```bash
export AI_DASHSCOPE_API_KEY=your-api-key-here
```

### 2. 编译运行

```bash
mvn clean compile
mvn spring-boot:run
```

### 3. 使用方式

**交互模式**（启动后直接对话）：

```
You: 北京天气怎么样
Agent: [search] Results for "北京天气": (mock result)
```

**单次执行**：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="你好，今天是几号？"
```

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

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.dashscope.api-key` | - | 通义千问 API Key（必填） |
| `spring.ai.dashscope.chat.options.model` | qwen-turbo | 模型名称 |
| `spring.ai.dashscope.chat.options.temperature` | 0.7 | 生成温度参数 |
| `agent.max-steps` | 10 | Agent 最大执行步数 |
| `agent.timeout-seconds` | 120 | 单次执行超时时间（秒） |

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
│  - WebSearchTool / MathTool / ...         │
│  - AgentProperties (配置)                 │
└─────────────────────────────────────────────┘
```

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

### 启用 MySQL 对话记忆

1. 创建数据库 `minimal_agent`
2. 取消注释 `application.yml` 中的 datasource 配置
3. 确保 MySQL 服务运行

## 常见问题

**Q: 运行时提示 `api-key` 错误？**  
A: 检查 `application.yml` 中的 API Key 是否正确，或确认环境变量 `AI_DASHSCOPE_API_KEY` 已设置。

**Q: 工具未被调用？**  
A: 检查 `@Tool` 的 `description` 是否清晰描述了工具用途，模型依赖描述来决定调用哪个工具。

**Q: 如何增加工具调用的最大次数？**  
A: 在 `application.yml` 中修改 `agent.max-steps: 20`。
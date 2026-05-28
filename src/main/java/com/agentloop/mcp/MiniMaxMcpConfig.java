package com.agentloop.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * MiniMax MCP (Model Context Protocol) 配置类。
 *
 * 通过 stdio 模式启动 minimax-coding-plan-mcp 子进程，
 * Spring AI MCP Client 自动发现工具并注册为 ToolCallbackProvider。
 *
 * 环境变量（启动前必须设置）：
 * - MINIMAX_API_KEY: MiniMax Token Plan Key（必填）
 * - MINIMAX_API_HOST: API 端点，默认 https://api.minimaxi.com
 * - MINIMAX_MCP_BASE_PATH: 本地输出目录（需存在且可写）
 */
@Configuration
@Slf4j
public class MiniMaxMcpConfig {

    public MiniMaxMcpConfig() {
        log.info("MiniMax MCP config initialized (stdio subprocess auto-starts on first use)");
    }
}
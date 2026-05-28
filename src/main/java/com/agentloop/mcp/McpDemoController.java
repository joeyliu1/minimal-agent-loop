package com.agentloop.mcp;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MiniMax MCP 工具调用演示 Controller。
 *
 * 提供两个端点演示 MiniMax Token Plan MCP 的能力：
 * - web_search: 网络搜索
 * - understand_image: 图片理解（支持 HTTP URL 或本地文件路径）
 *
 * 启动应用后访问：http://localhost:8080/mcp/demo
 */
@Slf4j
@RestController
@RequestMapping("/mcp/demo")
public class McpDemoController {

    private final ChatModel chatModel;
    private final ToolCallbackProvider tools;

    public McpDemoController(ChatModel chatModel, ToolCallbackProvider tools) {
        this.chatModel = chatModel;
        this.tools = tools;
    }

    /**
     * 演示 web_search 工具（网络搜索）。
     *
     * @param query 搜索关键词
     * @return 搜索结果
     */
    @GetMapping("/search")
    public Map<String, Object> webSearch(@RequestParam String query) {
        log.info("MCP web_search demo called with query: {}", query);
        try {
            String result = ChatClient.builder(chatModel)
                    .defaultToolCallbacks(tools)
                    .build()
                    .prompt()
                    .user("请使用 web_search 工具搜索：「" + query + "」，只返回搜索结果摘要，不超过100字")
                    .call()
                    .content();
            return Map.of("tool", "web_search", "query", query, "result", result);
        } catch (Exception e) {
            log.error("web_search error: {}", e.getMessage(), e);
            return Map.of("tool", "web_search", "query", query, "error", e.getMessage());
        }
    }

    /**
     * 演示 understand_image 工具（图片理解）。
     *
     * @param imageUrl 图片地址（HTTP/HTTPS URL 或本地文件绝对路径）
     * @param prompt   对图片的提问
     * @return 图片分析结果
     */
    @GetMapping("/image")
    public Map<String, Object> understandImage(
            @RequestParam String imageUrl,
            @RequestParam(defaultValue = "描述这张图片的内容") String prompt) {
        log.info("MCP understand_image demo called: {}", imageUrl);
        try {
            String result = ChatClient.builder(chatModel)
                    .defaultToolCallbacks(tools)
                    .build()
                    .prompt()
                    .user("请使用 understand_image 工具分析图片：\n" +
                          "图片地址：" + imageUrl + "\n" +
                          "分析要求：" + prompt)
                    .call()
                    .content();
            return Map.of("tool", "understand_image", "imageUrl", imageUrl, "prompt", prompt, "result", result);
        } catch (Exception e) {
            log.error("understand_image error: {}", e.getMessage(), e);
            return Map.of("tool", "understand_image", "imageUrl", imageUrl, "prompt", prompt, "error", e.getMessage());
        }
    }

    /**
     * MCP 工具帮助信息。
     */
    @GetMapping("/help")
    public Map<String, Object> help() {
        return Map.of(
                "description", "MiniMax Token Plan MCP 演示端点",
                "endpoints", Map.of(
                        "/mcp/demo/search", Map.of(
                                "method", "GET",
                                "params", "query (必填) - 搜索关键词",
                                "example", "/mcp/demo/search?query=今天天气"),
                        "/mcp/demo/image", Map.of(
                                "method", "GET",
                                "params", "imageUrl (必填), prompt (可选，默认描述图片内容)",
                                "example", "/mcp/demo/image?imageUrl=https://example.com/image.png&prompt=这张图片里有什么"),
                        "/mcp/demo/help", Map.of(
                                "method", "GET",
                                "description", "显示本帮助信息")
                ),
                "mcp_tools", Map.of(
                        "web_search", "对关键词进行网络搜索，返回搜索结果摘要",
                        "understand_image", "对图片进行理解和分析，支持 HTTP URL 或本地文件路径（JPEG/PNG/GIF/WebP，最大 20MB）"
                )
        );
    }
}
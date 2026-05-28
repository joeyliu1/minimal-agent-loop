package com.agentloop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Web search tool (mock implementation).
 */
@Component
@Slf4j
public class WebSearchTool {

    @Tool(name = "search", description = "Search the web for a query string")
    public String apply(@ToolParam(description = "search query") String query) {
        if (query == null || query.isBlank()) {
            return "[error] empty query";
        }
        log.info("WebSearchTool invoked: {}", query);
        return "[search] Results for \"" + query + "\": (mock result)";
    }
}
package com.agentloop.controller;

import com.agentloop.rag.IndexingService;
import com.agentloop.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Web UI controller for agent interaction.
 */
@RestController
public class WebController {

    private final AgentService agentService;
    private final IndexingService indexingService;

    public WebController(AgentService agentService, IndexingService indexingService) {
        this.agentService = agentService;
        this.indexingService = indexingService;
    }

    @GetMapping("/")
    public void index(HttpServletResponse response) throws Exception {
        response.sendRedirect("/index.html");
    }

    @GetMapping("/knowledge")
    public void knowledge(HttpServletResponse response) throws Exception {
        response.sendRedirect("/knowledge.html");
    }

    @PostMapping("/api/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String response = agentService.execute(message);
        return Map.of("response", response);
    }

    @PostMapping("/api/rag/add")
    public Map<String, String> ragAdd(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String source = request.getOrDefault("source", "用户添加");
        indexingService.addDocument(content, source);
        return Map.of("status", "ok", "message", "已添加: " + content);
    }

    @DeleteMapping("/api/rag/clear")
    public Map<String, String> ragClear() {
        indexingService.clear();
        return Map.of("status", "ok", "message", "知识库已清空");
    }

    @DeleteMapping("/api/rag/delete")
    public Map<String, String> ragDelete(@RequestParam("id") String id) {
        indexingService.deleteDocument(id);
        return Map.of("status", "ok", "message", "已删除");
    }

    @GetMapping("/api/rag/list")
    public Map<String, Object> ragList() {
        var docs = indexingService.listDocuments();
        return Map.of("status", "ok", "count", docs.size(), "documents", docs);
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
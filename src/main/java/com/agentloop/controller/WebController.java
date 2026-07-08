package com.agentloop.controller;

import com.agentloop.memory.ChatMemoryService;
import com.agentloop.rag.DocumentParser;
import com.agentloop.rag.IndexingService;
import com.agentloop.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Web UI controller for agent interaction.
 */
@RestController
public class WebController {

    private final AgentService agentService;
    private final IndexingService indexingService;
    private final DocumentParser documentParser;
    private final ChatMemoryService memoryService;

    public WebController(AgentService agentService, IndexingService indexingService,
                         DocumentParser documentParser, ChatMemoryService memoryService) {
        this.agentService = agentService;
        this.indexingService = indexingService;
        this.documentParser = documentParser;
        this.memoryService = memoryService;
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
    public Map<String, String> chat(@RequestBody Map<String, Object> request) {
        String message = String.valueOf(request.getOrDefault("message", ""));
        boolean useKnowledgeBase = Boolean.parseBoolean(String.valueOf(request.getOrDefault("useKnowledgeBase", true)));
        String knowledgeBaseId = String.valueOf(request.getOrDefault("knowledgeBaseId", "default"));
        String sessionId = String.valueOf(request.getOrDefault("sessionId", "default-session-" + System.currentTimeMillis()));
        String response = agentService.execute(message, useKnowledgeBase, knowledgeBaseId, sessionId);
        return Map.of("response", response);
    }

    @PostMapping("/api/rag/upload")
    public Map<String, Object> ragUpload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "knowledgeBaseId", defaultValue = "default") String knowledgeBaseId
    ) {
        if (files == null || files.isEmpty()) {
            return Map.of("status", "error", "message", "请选择文件");
        }
        int success = 0;
        int failed = 0;
        StringBuilder msg = new StringBuilder();
        for (MultipartFile file : files) {
            try {
                String content = documentParser.parse(file);
                if (content != null && !content.isBlank()) {
                    indexingService.addDocument(content, "文件上传: " + file.getOriginalFilename(), knowledgeBaseId);
                    success++;
                    msg.append("✓ ").append(file.getOriginalFilename()).append("\n");
                } else {
                    failed++;
                    msg.append("✗ ").append(file.getOriginalFilename()).append(" (内容为空)\n");
                }
            } catch (Exception e) {
                failed++;
                msg.append("✗ ").append(file.getOriginalFilename()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return Map.of(
            "status", failed == 0 ? "ok" : "partial",
            "success", success,
            "failed", failed,
            "message", msg.toString()
        );
    }

    @PostMapping("/api/rag/add")
    public Map<String, String> ragAdd(@RequestBody Map<String, String> request) {
        String content = request == null ? null : request.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("status", "error", "message", "content 不能为空");
        }
        String source = request.getOrDefault("source", "用户添加");
        String knowledgeBaseId = request.getOrDefault("knowledgeBaseId", "default");
        indexingService.addDocument(content, source, knowledgeBaseId);
        return Map.of("status", "ok", "message", "已添加");
    }

    @DeleteMapping("/api/rag/clear")
    public Map<String, String> ragClear(@RequestParam(value = "knowledgeBaseId", defaultValue = "default") String knowledgeBaseId) {
        indexingService.clear(knowledgeBaseId);
        return Map.of("status", "ok", "message", "知识库已清空");
    }

    @DeleteMapping("/api/rag/delete")
    public Map<String, String> ragDelete(@RequestParam("id") String id) {
        indexingService.deleteDocument(id);
        return Map.of("status", "ok", "message", "已删除");
    }

    @GetMapping("/api/rag/list")
    public Map<String, Object> ragList(@RequestParam(value = "knowledgeBaseId", defaultValue = "default") String knowledgeBaseId) {
        var docs = indexingService.listDocuments(knowledgeBaseId);
        return Map.of("status", "ok", "count", docs.size(), "documents", docs);
    }

    @GetMapping("/api/rag/kbs")
    public Map<String, Object> ragKnowledgeBases() {
        var knowledgeBases = indexingService.listKnowledgeBases();
        return Map.of("status", "ok", "count", knowledgeBases.size(), "knowledgeBases", knowledgeBases);
    }

    @PostMapping("/api/rag/kbs")
    public Map<String, Object> ragCreateKnowledgeBase(@RequestBody Map<String, String> request) {
        String name = request == null ? "" : request.getOrDefault("name", "");
        String description = request == null ? "" : request.getOrDefault("description", "");
        var knowledgeBase = indexingService.createKnowledgeBase(name, description);
        return Map.of("status", "ok", "knowledgeBase", knowledgeBase);
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // ── Conversation history ──────────────────────────────────────────────

    @GetMapping("/api/sessions")
    public Map<String, Object> listSessions() {
        var sessions = memoryService.listSessions();
        return Map.of("status", "ok", "count", sessions.size(), "sessions", sessions);
    }

    @GetMapping("/api/sessions/{sessionId}/messages")
    public Map<String, Object> getSessionMessages(@PathVariable String sessionId) {
        var messages = memoryService.getRecentMessages(sessionId);
        return Map.of("status", "ok", "sessionId", sessionId, "messages", messages);
    }

    @DeleteMapping("/api/sessions")
    public Map<String, String> deleteSession(@RequestParam String sessionId) {
        memoryService.clearSession(sessionId);
        return Map.of("status", "ok", "message", "会话已删除");
    }
}

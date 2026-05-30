package com.agentloop.controller;

import com.agentloop.memory.ChatMemoryService;
import com.agentloop.memory.ChatSessionService;
import com.agentloop.memory.ChatSessionService.Session;
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
 * Supports multi-session chat with MySQL-backed memory.
 */
@RestController
public class WebController {

    private final AgentService agentService;
    private final IndexingService indexingService;
    private final DocumentParser documentParser;
    private final ChatSessionService sessionService;
    private final ChatMemoryService memoryService;

    public WebController(
            AgentService agentService,
            IndexingService indexingService,
            DocumentParser documentParser,
            ChatSessionService sessionService,
            ChatMemoryService memoryService
    ) {
        this.agentService = agentService;
        this.indexingService = indexingService;
        this.documentParser = documentParser;
        this.sessionService = sessionService;
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

    // ── Session management ──────────────────────────────────────────

    @PostMapping("/api/session")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "新对话");
        Session session = sessionService.createSession(title);
        return Map.of("status", "ok", "sessionId", session.sessionId(), "title", session.title());
    }

    @GetMapping("/api/sessions")
    public Map<String, Object> listSessions() {
        List<Session> sessions = sessionService.listSessions();
        return Map.of(
            "status", "ok",
            "count", sessions.size(),
            "sessions", sessions.stream()
                .map(s -> Map.of(
                    "sessionId", s.sessionId(),
                    "title", s.title(),
                    "createdAt", s.createdAt(),
                    "updatedAt", s.updatedAt()
                )).toList()
        );
    }

    @PutMapping("/api/session/{sessionId}")
    public Map<String, String> renameSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body
    ) {
        String title = body.getOrDefault("title", "新对话");
        sessionService.renameSession(sessionId, title);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/api/session/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return Map.of("status", "ok");
    }

    // ── Chat with session ───────────────────────────────────────────

    /**
     * POST /api/chat
     * Body: { "message": "...", "sessionId": "optional" }
     * If sessionId is absent or empty, a NEW session is created automatically.
     * Each aside-button click → no sessionId → creates a fresh session.
     */
    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        // No sessionId → brand new session (aside button clicks land here)
        if (sessionId == null || sessionId.isBlank()) {
            Session session = sessionService.createSession(sessionService.generateTitle(message));
            sessionId = session.sessionId();
            memoryService.addMessage(sessionId, "user", message);
            String response = agentService.execute(sessionId, message);
            memoryService.addMessage(sessionId, "assistant", response);
            sessionService.touchSession(sessionId);
            return Map.of(
                "status", "ok",
                "sessionId", sessionId,
                "response", response
            );
        }

        // Existing session
        memoryService.addMessage(sessionId, "user", message);
        String response = agentService.execute(sessionId, message);
        memoryService.addMessage(sessionId, "assistant", response);
        sessionService.touchSession(sessionId);
        return Map.of("status", "ok", "sessionId", sessionId, "response", response);
    }

    /**
     * GET /api/session/{sessionId}/messages
     * Returns messages for a given session (newest last, for ChatClient ordering).
     */
    @GetMapping("/api/session/{sessionId}/messages")
    public Map<String, Object> getMessages(@PathVariable String sessionId) {
        var msgs = memoryService.getRecentMessages(sessionId);
        return Map.of(
            "status", "ok",
            "messages", msgs.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList()
        );
    }

    // ── RAG ────────────────────────────────────────────────────────

    @PostMapping("/api/rag/upload")
    public Map<String, Object> ragUpload(@RequestParam("files") List<MultipartFile> files) {
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
                    indexingService.addDocument(content, "文件上传: " + file.getOriginalFilename());
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
package com.agentloop.controller;

import com.agentloop.memory.ChatMemoryService;
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
    private final ChatMemoryService memoryService;

    public WebController(AgentService agentService, ChatMemoryService memoryService) {
        this.agentService = agentService;
        this.memoryService = memoryService;
    }

    @GetMapping("/")
    public void index(HttpServletResponse response) throws Exception {
        response.sendRedirect("/index.html");
    }

    @PostMapping("/api/chat")
    public Map<String, String> chat(@RequestBody Map<String, Object> request) {
        String message = String.valueOf(request.getOrDefault("message", ""));
        String sessionId = String.valueOf(
            request.getOrDefault("sessionId", "default-session-" + System.currentTimeMillis()));
        String response = agentService.execute(message, sessionId);
        return Map.of("response", response);
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

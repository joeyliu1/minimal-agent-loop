package com.agentloop.controller;

import com.agentloop.agent.AgentLoopState;
import com.agentloop.agent.AgentStreamObserver;
import com.agentloop.memory.ChatMemoryService;
import com.agentloop.service.AgentService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web UI controller for agent interaction.
 */
@RestController
public class WebController {

    private final AgentService agentService;
    private final ChatMemoryService memoryService;
    private final ExecutorService streamExecutor;
    private static final AtomicInteger STREAM_COUNTER = new AtomicInteger();

    public WebController(AgentService agentService, ChatMemoryService memoryService) {
        this.agentService = agentService;
        this.memoryService = memoryService;
        this.streamExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "chat-stream-" + STREAM_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
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

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody Map<String, Object> request,
            HttpServletResponse httpResponse) {

        String message = String.valueOf(request.getOrDefault("message", ""));
        String sessionId = String.valueOf(
                request.getOrDefault("sessionId", "default-session-" + System.currentTimeMillis()));

        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(130_000L);
        streamExecutor.submit(() -> {
            try {
                sendEvent(emitter, "ready", Map.of("sessionId", sessionId));
                String response = agentService.executeStreaming(
                        message,
                        sessionId,
                        new AgentStreamObserver() {
                            @Override
                            public void onState(AgentLoopState state, int step) {
                                sendEventUnchecked(emitter, "state", Map.of(
                                        "state", state.name(),
                                        "step", step));
                            }

                            @Override
                            public void onToken(String token) {
                                sendEventUnchecked(emitter, "token", Map.of("content", token));
                            }

                            @Override
                            public void onReset() {
                                sendEventUnchecked(emitter, "reset", Map.of("reason", "new-attempt"));
                            }
                        });
                sendEvent(emitter, "done", Map.of("response", response));
                emitter.complete();
            } catch (Exception error) {
                try {
                    sendEvent(emitter, "error", Map.of(
                            "message", error.getMessage() != null
                                    ? error.getMessage()
                                    : "Agent stream failed"));
                } catch (Exception ignored) {
                    // The browser may already have closed the connection.
                }
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data)
            throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private static void sendEventUnchecked(SseEmitter emitter, String name, Object data) {
        try {
            sendEvent(emitter, name, data);
        } catch (IOException error) {
            throw new IllegalStateException("SSE client disconnected", error);
        }
    }

    @PreDestroy
    public void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
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

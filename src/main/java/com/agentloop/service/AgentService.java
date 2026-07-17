package com.agentloop.service;

import com.agentloop.agent.AgentContext;
import com.agentloop.agent.AgentMetrics;
import com.agentloop.agent.AgentOrchestrator;
import com.agentloop.agent.AgentStreamObserver;
import com.agentloop.config.AgentProperties;
import com.agentloop.memory.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * Facade for agent execution.
 * <p>Builds {@link AgentContext} from user inputs, delegates to
 * {@link AgentOrchestrator}, and persists conversation pairs.</p>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentOrchestrator orchestrator;
    private final ChatMemoryService memoryService;
    private final AgentMetrics metrics;
    private final int maxSteps;
    private final long timeoutSeconds;
    private final long stepTimeoutMs;

    public AgentService(
            AgentOrchestrator orchestrator,
            ChatMemoryService memoryService,
            AgentMetrics metrics,
            AgentProperties properties) {

        this.orchestrator = orchestrator;
        this.memoryService = memoryService;
        this.metrics = metrics;
        this.maxSteps = properties.getMaxSteps();
        this.timeoutSeconds = properties.getTimeoutSeconds();
        this.stepTimeoutMs = properties.getStepTimeoutMs();

        log.info("AgentService initialized: maxSteps={}, totalTimeout={}s, stepTimeout={}ms",
                maxSteps, timeoutSeconds, stepTimeoutMs);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public String execute(String userMessage, String sessionId) {
        return execute(userMessage, sessionId, null);
    }

    public String executeStreaming(
            String userMessage,
            String sessionId,
            AgentStreamObserver observer) {
        return execute(userMessage, sessionId, observer);
    }

    private String execute(
            String userMessage,
            String sessionId,
            AgentStreamObserver streamObserver) {
        long startTime = System.currentTimeMillis();
        log.info("AgentService.execute: msg='{}' sessionId={}",
                truncate(userMessage, 100), sessionId);

        // Build context
        AgentContext ctx = AgentContext.builder()
                .sessionId(sessionId)
                .maxSteps(maxSteps)
                .stepTimeoutMs(stepTimeoutMs)
                .totalTimeoutMs(timeoutSeconds * 1000)
                .build();

        // Add user message as initial context
        ctx.addMessage(new UserMessage(userMessage));

        try {
            // Execute via orchestrator (state machine)
            String response = streamObserver == null
                    ? orchestrator.execute(ctx)
                    : orchestrator.executeStreaming(ctx, streamObserver);

            // Persist to memory
            memoryService.addMessage(sessionId, "user", userMessage);
            memoryService.addMessage(sessionId, "assistant", response);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AgentService.execute completed in {}ms for session {}",
                    elapsed, sessionId);

            return response;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("AgentService.execute failed after {}ms: {}",
                    elapsed, e.getMessage(), e);
            return "[error] " + e.getMessage();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

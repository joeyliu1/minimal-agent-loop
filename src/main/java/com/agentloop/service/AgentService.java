package com.agentloop.service;

import com.agentloop.agent.AgentContext;
import com.agentloop.agent.AgentMetrics;
import com.agentloop.agent.AgentOrchestrator;
import com.agentloop.config.AgentProperties;
import com.agentloop.memory.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * Facade for agent execution.
 * <p>Maintains backward-compatible {@code execute()} signatures while delegating
 * the actual agent loop to {@link AgentOrchestrator}.</p>
 *
 * <h3>Responsibility</h3>
 * <ul>
 *   <li>Build {@link AgentContext} from user inputs</li>
 *   <li>Save the user message into the context</li>
 *   <li>Call orchestrator</li>
 *   <li>Persist the user/assistant pair into {@link ChatMemoryService}</li>
 * </ul>
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
        // Default: each step up to 30s, total timeout from properties
        this.stepTimeoutMs = 30_000;

        log.info("AgentService initialized (facade mode): maxSteps={}, totalTimeout={}s, stepTimeout={}ms",
                maxSteps, timeoutSeconds, stepTimeoutMs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API (backward-compatible)
    // ═══════════════════════════════════════════════════════════════════════

    public String execute(String userMessage) {
        return execute(userMessage, true, null);
    }

    public String execute(String userMessage, boolean useKnowledgeBase) {
        return execute(userMessage, useKnowledgeBase, null);
    }

    public String execute(String userMessage, boolean useKnowledgeBase, String knowledgeBaseId) {
        return execute(userMessage, useKnowledgeBase, knowledgeBaseId,
                "default-session-" + System.currentTimeMillis());
    }

    public String execute(String userMessage, boolean useKnowledgeBase,
                          String knowledgeBaseId, String sessionId) {
        long startTime = System.currentTimeMillis();
        log.info("AgentService.execute: msg='{}' useKnowledgeBase={} kbId={} sessionId={}",
                truncate(userMessage, 100), useKnowledgeBase, knowledgeBaseId, sessionId);

        // Build AgentContext
        AgentContext ctx = AgentContext.builder()
                .sessionId(sessionId)
                .knowledgeBaseId(knowledgeBaseId)
                .useKnowledgeBase(useKnowledgeBase)
                .maxSteps(maxSteps)
                .stepTimeoutMs(stepTimeoutMs)
                .totalTimeoutMs(timeoutSeconds * 1000)
                .build();

        // Add the user message as the initial context
        ctx.addMessage(new UserMessage(userMessage));

        try {
            // Execute via orchestrator (state machine)
            String response = orchestrator.execute(ctx);

            // Persist to memory
            memoryService.addMessage(sessionId, "user", userMessage);
            memoryService.addMessage(sessionId, "assistant", response);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AgentService.execute completed in {}ms for session {}", elapsed, sessionId);

            return response;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("AgentService.execute failed after {}ms: {}", elapsed, e.getMessage(), e);
            return "[error] " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

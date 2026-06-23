package com.agentloop.agent;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resilient wrapper around {@link ToolCallingManager#executeToolCalls}.
 * <p>Applies retry (exponential backoff) and rate limiting for every tool execution.
 * All tool calls from a single LLM response are executed in one batch.</p>
 */
@Component
public class ResilientToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolExecutor.class);

    private final ToolCallingManager toolCallingManager;
    private final AgentMetrics metrics;

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    private static final int MAX_RETRIES = 2;

    public ResilientToolExecutor(ToolCallingManager toolCallingManager, AgentMetrics metrics) {
        this.toolCallingManager = toolCallingManager;
        this.metrics = metrics;
    }

    /**
     * Execute all tool calls contained in the assistant message with retry + rate limit.
     *
     * @return conversation history messages, or empty list if all attempts failed
     */
    public List<Message> execute(AssistantMessage assistantMessage, ChatResponse response, AgentContext ctx) {
        var toolCalls = assistantMessage.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();

        // Record metrics per tool name
        for (var tc : toolCalls) {
            metrics.recordToolCall(tc.name());
        }

        // Check rate limiters for all tools
        for (var tc : toolCalls) {
            RateLimiter rl = getRateLimiter(tc.name());
            if (!rl.acquirePermission()) {
                log.warn("Rate limit exceeded for tool: {}", tc.name());
                metrics.recordToolError(tc.name());
                // Rate limited — let the orchestrator handle it (return empty = no result)
                return List.of();
            }
        }

        // Retry loop for the entire batch
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (mdcContext != null) MDC.setContextMap(mdcContext);

                if (attempt > 1) {
                    long delay = 500L * (1L << (attempt - 2));
                    log.info("Retry {}/{} for {} tool(s) after {}ms",
                            attempt, MAX_RETRIES, toolCalls.size(), delay);
                    Thread.sleep(delay);
                }

                Timer.Sample toolTimer = metrics.startToolTimer();
                long startMs = System.currentTimeMillis();

                try {
                    var result = toolCallingManager.executeToolCalls(
                            new Prompt(List.of(assistantMessage)),
                            response
                    );

                    long elapsed = System.currentTimeMillis() - startMs;
                    metrics.stopToolTimer(toolTimer);

                    int historySize = (result != null && result.conversationHistory() != null)
                            ? result.conversationHistory().size() : 0;

                    if (historySize > 0) {
                        log.info("Tool batch executed in {}ms ({} result messages)", elapsed, historySize);
                        return result.conversationHistory();
                    }
                    log.info("Tool batch executed in {}ms (no result messages)", elapsed);
                    return List.of();

                } finally {
                    metrics.stopToolTimer(toolTimer);
                }

            } catch (Exception e) {
                lastError = e;
                log.warn("Tool batch attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
            } finally {
                MDC.clear();
                if (mdcContext != null) MDC.setContextMap(mdcContext);
            }
        }

        // All retries exhausted
        for (var tc : toolCalls) {
            metrics.recordToolError(tc.name());
        }
        log.error("Tool batch failed after {} retries: {}", MAX_RETRIES,
                lastError != null ? lastError.getMessage() : "unknown");
        return List.of();
    }

    // ── Rate limiter factory ────────────────────────────────────────────

    private RateLimiter getRateLimiter(String toolName) {
        return rateLimiters.computeIfAbsent(toolName, name -> {
            RateLimiterConfig config = RateLimiterConfig.custom()
                    .limitForPeriod(20)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ofMillis(50))
                    .build();
            return RateLimiter.of("tool-" + name, config);
        });
    }
}

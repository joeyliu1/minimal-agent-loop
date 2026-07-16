package com.agentloop.agent;

import org.slf4j.MDC;
import org.springframework.ai.chat.messages.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-execution context for a single agent loop.
 * Immutable-except-state: message list and metadata are mutable; state follows
 * the transition matrix defined by {@link AgentLoopState}.
 */
public class AgentContext {

    private final String sessionId;
    private final String traceId;
    private final int maxSteps;
    private final long stepTimeoutMs;
    private final long totalTimeoutMs;
    private final List<Message> messages;
    private final Map<String, Object> metadata;
    private AgentLoopState state;
    private int currentStep;
    private long startTimeNanos;
    private long stepStartTimeNanos;

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private int maxSteps = 10;
        private long stepTimeoutMs = 30_000;
        private long totalTimeoutMs = 120_000;

        Builder() {}

        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder maxSteps(int maxSteps) { this.maxSteps = maxSteps; return this; }
        public Builder stepTimeoutMs(long stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; return this; }
        public Builder totalTimeoutMs(long totalTimeoutMs) { this.totalTimeoutMs = totalTimeoutMs; return this; }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    private AgentContext(Builder b) {
        this.sessionId = b.sessionId != null ? b.sessionId : UUID.randomUUID().toString();
        this.traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.maxSteps = b.maxSteps;
        this.stepTimeoutMs = b.stepTimeoutMs;
        this.totalTimeoutMs = b.totalTimeoutMs;
        this.messages = new ArrayList<>();
        this.metadata = new ConcurrentHashMap<>();
        this.state = AgentLoopState.INIT;
        this.currentStep = 0;

        // Seed metadata
        metadata.put("traceId", traceId);
        metadata.put("sessionId", this.sessionId);

        // Set MDC for logging
        MDC.put("traceId", traceId);
        MDC.put("sessionId", this.sessionId);
    }

    // ── State transitions ────────────────────────────────────────────────────

    public AgentLoopState getState() { return state; }

    public void transitionTo(AgentLoopState next) {
        AgentLoopState.guard(state, next);
        this.state = next;
    }

    public boolean isTerminal() {
        return state == AgentLoopState.FINISHED || state == AgentLoopState.ERROR;
    }

    // ── Step management ──────────────────────────────────────────────────────

    public int getCurrentStep() { return currentStep; }
    public void incrementStep() { currentStep++; }
    public int getMaxSteps() { return maxSteps; }
    public boolean hasMoreSteps() { return currentStep < maxSteps; }

    // ── Timing ────────────────────────────────────────────────────────────────

    public long getStepTimeoutMs() { return stepTimeoutMs; }
    public long getTotalTimeoutMs() { return totalTimeoutMs; }

    public void markLoopStart() { this.startTimeNanos = System.nanoTime(); }
    public void markStepStart() { this.stepStartTimeNanos = System.nanoTime(); }
    public long elapsedLoopMs() { return (System.nanoTime() - startTimeNanos) / 1_000_000; }
    public long elapsedStepMs() { return (System.nanoTime() - stepStartTimeNanos) / 1_000_000; }

    // ── Messages ─────────────────────────────────────────────────────────────

    public List<Message> getMessages() { return messages; }
    public void addMessage(Message msg) { messages.add(msg); }
    public void addAllMessages(List<? extends Message> msgs) { messages.addAll(msgs); }

    // ── Metadata ─────────────────────────────────────────────────────────────

    public Map<String, Object> getMetadata() { return metadata; }
    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key) { return (T) metadata.get(key); }
    public void setMeta(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getSessionId() { return sessionId; }
    public String getTraceId() { return traceId; }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    public void cleanup() {
        MDC.remove("traceId");
        MDC.remove("sessionId");
    }
}

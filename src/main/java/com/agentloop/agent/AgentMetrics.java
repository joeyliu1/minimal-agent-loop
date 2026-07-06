package com.agentloop.agent;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent-level Micrometer metrics.
 * <p>Exposed via {@code /actuator/prometheus}.</p>
 *
 * <pre>
 * agent_loop_total{status="success|error"}
 * agent_loop_duration_seconds
 * agent_step_duration_seconds{state,step}
 * agent_tool_calls_total{tool}
 * agent_tool_duration_seconds{tool}
 * agent_tool_errors_total{tool}
 * agent_context_size
 * </pre>
 */
@Component
public class AgentMetrics implements MeterBinder {

    private MeterRegistry registry;

    private Counter loopSuccess;
    private Counter loopError;
    private Timer loopDuration;
    private Timer stepDuration;
    private Timer toolDuration;
    private final AtomicInteger contextSizeGauge = new AtomicInteger(0);

    // Tagged meters (lazy, cached)
    private final ConcurrentMap<String, Counter> toolCallCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> toolErrorCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> toolTimers = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry r) {
        this.registry = r;

        loopSuccess = Counter.builder("agent.loop.total")
                .tag("status", "success")
                .description("Total successful agent loop invocations")
                .register(r);

        loopError = Counter.builder("agent.loop.total")
                .tag("status", "error")
                .description("Total failed agent loop invocations")
                .register(r);

        loopDuration = Timer.builder("agent.loop.duration")
                .description("Agent loop execution duration (seconds)")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(r);

        stepDuration = Timer.builder("agent.step.duration")
                .description("Per agent-step duration (seconds)")
                .publishPercentileHistogram()
                .register(r);

        toolDuration = Timer.builder("agent.tool.duration")
                .description("Tool execution duration (seconds)")
                .publishPercentileHistogram()
                .register(r);

        Gauge.builder("agent.context.size", contextSizeGauge, AtomicInteger::get)
                .description("Current context message count")
                .register(r);
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    public void recordLoopEnd(boolean success) {
        if (success) loopSuccess.increment(); else loopError.increment();
    }

    public Timer.Sample startLoopTimer() {
        return Timer.start(registry);
    }

    public void stopLoopTimer(Timer.Sample sample) {
        if (sample != null) sample.stop(loopDuration);
    }

    // ── Step ──────────────────────────────────────────────────────────────────

    public Timer.Sample startStepTimer() {
        return Timer.start(registry);
    }

    public void stopStepTimer(Timer.Sample sample) {
        if (sample != null) sample.stop(stepDuration);
    }

    // ── Tool ─────────────────────────────────────────────────────────────────

    public void recordToolCall(String toolName) {
        toolCallCounters.computeIfAbsent(toolName, n ->
                Counter.builder("agent.tool.calls")
                        .tag("tool", n)
                        .description("Tool call count per tool")
                        .register(registry)
        ).increment();
    }

    public void recordToolError(String toolName) {
        toolErrorCounters.computeIfAbsent(toolName, n ->
                Counter.builder("agent.tool.errors")
                        .tag("tool", n)
                        .description("Tool error count per tool")
                        .register(registry)
        ).increment();
    }

    public Timer.Sample startToolTimer() {
        return Timer.start(registry);
    }

    public void stopToolTimer(Timer.Sample sample) {
        if (sample != null) sample.stop(toolDuration);
    }

    // ── Context ──────────────────────────────────────────────────────────────

    public void updateContextSize(int size) {
        contextSizeGauge.set(size);
    }
}

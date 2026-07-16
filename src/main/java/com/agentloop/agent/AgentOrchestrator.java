package com.agentloop.agent;

import com.agentloop.config.AgentProperties;
import com.agentloop.memory.ChatMemoryService;
import com.agentloop.tools.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State-machine-driven agent orchestrator.
 * <p>Implements a clean ReAct-style Agent Loop:
 * <pre>
 * THINKING → LLM responds with tool calls?
 *   YES → TOOL_CALLING → execute tools → append results → THINKING (repeat)
 *   NO  → RESPONDING → return text answer
 * </pre></p>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    // ═══════════════════════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════════════════════

    private final ChatClient chatClient;
    private final ChatMemoryService memoryService;
    private final ResilientToolExecutor toolExecutor;
    private final AgentMetrics metrics;
    private final int llmRetries;

    private final ExecutorService loopExecutor;

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════

    public AgentOrchestrator(
            ChatMemoryService memoryService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            ResilientToolExecutor toolExecutor,
            AgentMetrics metrics,
            WebSearchTool webSearchTool,
            MathTool mathTool,
            FileReadTool fileReadTool,
            CurrentDateTool currentDateTool,
            AgentProperties properties) {

        this.memoryService = memoryService;
        this.toolExecutor = toolExecutor;
        this.metrics = metrics;
        this.llmRetries = Math.max(1, properties.getLlmRetries());

        // Single ChatClient with full tool set
        this.chatClient = chatClientBuilderProvider.getObject()
                .defaultSystem("""
                        你是一个通用智能助手，基于自身能力直接回答用户问题。

                        【行为准则】
                        1. 直接作答：除非需要计算、查询实时信息或读取文件，否则直接用通识回答。
                        2. 工具分工：数学计算→calculator；日期时间→get_date；读取本地文件→read_file；外部信息→search。
                        3. 引用标注：引用工具结果时注明来源。
                        4. 复杂问题先规划：多步推理问题，先简述思路再分步执行。
                        5. 坦诚边界：不确定时明确说明，不假装权威。
                        """)
                .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool)
                .build();

        // Dedicated executor for agent loops
        this.loopExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-loop-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        log.info("AgentOrchestrator initialized");
    }

    private static final AtomicInteger counter = new AtomicInteger(0);

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute the agent loop synchronously.
     * Blocks up to ctx.totalTimeoutMs via an internal executor.
     */
    public String execute(AgentContext ctx) {
        log.info("AgentOrchestrator.execute: sessionId={}, traceId={}",
                ctx.getSessionId(), ctx.getTraceId());

        ctx.markLoopStart();

        Future<String> future = loopExecutor.submit(() -> runLoop(ctx));
        try {
            return future.get(ctx.getTotalTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent loop timed out after {}ms for session {}",
                    ctx.getTotalTimeoutMs(), ctx.getSessionId());
            ctx.transitionTo(AgentLoopState.ERROR);
            future.cancel(true);
            return "[timeout] agent loop exceeded " + ctx.getTotalTimeoutMs() / 1000 + "s";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("Agent loop execution error: {}",
                    cause != null ? cause.getMessage() : "unknown", cause);
            ctx.transitionTo(AgentLoopState.ERROR);
            return "[error] " + (cause != null ? cause.getMessage() : "unknown error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Agent loop interrupted");
            ctx.transitionTo(AgentLoopState.ERROR);
            return "[interrupted] request cancelled";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Core loop
    // ═══════════════════════════════════════════════════════════════════════

    private String runLoop(AgentContext ctx) {
        Timer.Sample loopTimer = metrics.startLoopTimer();
        boolean success = false;

        try {
            // INIT → THINKING
            ctx.transitionTo(AgentLoopState.THINKING);

            // Load session history from memory (inserted BEFORE current user message)
            int historySize = loadSessionHistory(ctx);
            log.info("runLoop: sessionId={}, historySize={}, totalMsgs={}",
                    ctx.getSessionId(), historySize, ctx.getMessages().size());

            metrics.updateContextSize(ctx.getMessages().size());

            // ── Main loop ────────────────────────────────────────────────
            while (ctx.hasMoreSteps() && !ctx.isTerminal()) {
                ctx.markStepStart();
                ctx.incrementStep();
                Timer.Sample stepTimer = metrics.startStepTimer();

                try {
                    // Guard: check total timeout
                    if (ctx.elapsedLoopMs() > ctx.getTotalTimeoutMs()) {
                        log.warn("Total timeout exceeded at step {}", ctx.getCurrentStep());
                        ctx.transitionTo(AgentLoopState.ERROR);
                        break;
                    }

                    // PHASE: THINKING — call LLM
                    log.debug("Step {}: calling LLM (state={})",
                            ctx.getCurrentStep(), ctx.getState());
                    ChatResponse response = callLlm(ctx);

                    if (response == null || response.getResult() == null) {
                        log.warn("Step {}: empty response from LLM", ctx.getCurrentStep());
                        ctx.transitionTo(AgentLoopState.ERROR);
                        break;
                    }

                    AssistantMessage assistantMessage = response.getResult().getOutput();

                    if (!assistantMessage.hasToolCalls()) {
                        // No tool calls → ready to respond
                        String content = assistantMessage.getText();
                        log.info("Step {}: LLM responded directly ({} chars)",
                                ctx.getCurrentStep(), content != null ? content.length() : 0);

                        ctx.transitionTo(AgentLoopState.RESPONDING);
                        persistMessages(ctx);

                        success = true;
                        return content != null ? content : "";
                    }

                    // PHASE: TOOL_CALLING — execute tools with resilience
                    log.info("Step {}: {} tool call(s) requested",
                            ctx.getCurrentStep(), assistantMessage.getToolCalls().size());

                    ctx.addMessage(assistantMessage);
                    ctx.transitionTo(AgentLoopState.TOOL_CALLING);

                    List<Message> toolResults = toolExecutor.execute(assistantMessage, response, ctx);

                    if (toolResults.isEmpty()) {
                        log.warn("Step {}: tool execution returned no results", ctx.getCurrentStep());
                    } else {
                        ctx.addAllMessages(toolResults);
                    }

                    // Back to THINKING for next iteration
                    ctx.transitionTo(AgentLoopState.THINKING);
                    metrics.updateContextSize(ctx.getMessages().size());

                } catch (Exception e) {
                    log.error("Step {} error: {}", ctx.getCurrentStep(), e.getMessage(), e);
                    ctx.transitionTo(AgentLoopState.ERROR);
                    break;
                } finally {
                    metrics.stopStepTimer(stepTimer);
                }
            }

            // ── Loop exhausted ───────────────────────────────────────────
            if (!ctx.isTerminal() && ctx.getState() != AgentLoopState.RESPONDING) {
                log.warn("Agent loop exhausted after {} steps", ctx.getMaxSteps());
                return "[max_steps] reached limit (" + ctx.getMaxSteps() + ")";
            }

            success = true;
            return "[error] unexpected termination in state " + ctx.getState();

        } finally {
            metrics.stopLoopTimer(loopTimer);
            metrics.recordLoopEnd(success);
            ctx.cleanup();
            log.info("Agent loop completed (success={}) for session {}", success, ctx.getSessionId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LLM call with retry
    // ═══════════════════════════════════════════════════════════════════════

    private ChatResponse callLlm(AgentContext ctx) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= llmRetries; attempt++) {
            try {
                if (attempt > 1) {
                    long delayMs = 500L * (1L << (attempt - 2));
                    log.info("Retry {}/{} for LLM call after {}ms",
                            attempt, llmRetries, delayMs);
                    Thread.sleep(delayMs);
                }
                return chatClient.prompt()
                        .messages(ctx.getMessages())
                        .advisors(new SimpleLoggerAdvisor())
                        .call()
                        .chatResponse();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM call interrupted", e);
            } catch (Exception e) {
                lastError = e;
                log.warn("LLM call attempt {}/{} failed at step {}: {}",
                        attempt, llmRetries, ctx.getCurrentStep(), e.getMessage());
            }
        }
        log.error("LLM call failed after {} attempt(s) at step {}: {}",
                llmRetries, ctx.getCurrentStep(),
                lastError != null ? lastError.getMessage() : "unknown");
        throw new RuntimeException(lastError != null ? lastError.getMessage() : "LLM call failed",
                lastError);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Memory helpers
    // ═══════════════════════════════════════════════════════════════════════

    private int loadSessionHistory(AgentContext ctx) {
        List<ChatMemoryService.ChatMessage> history =
                memoryService.getRecentMessages(ctx.getSessionId(), 20);

        List<Message> histMsgs = new ArrayList<>();
        for (ChatMemoryService.ChatMessage chatMsg : history) {
            if ("user".equalsIgnoreCase(chatMsg.role())) {
                histMsgs.add(new UserMessage(chatMsg.content()));
            } else if ("assistant".equalsIgnoreCase(chatMsg.role())) {
                histMsgs.add(new AssistantMessage(chatMsg.content()));
            }
        }
        // Current user message was pre-added by AgentService at index 0.
        // Insert history before it so the order is [history..., currentUser].
        ctx.getMessages().addAll(0, histMsgs);
        log.debug("Loaded {} historical messages for session {}",
                history.size(), ctx.getSessionId());
        return history.size();
    }

    private void persistMessages(AgentContext ctx) {
        // Currently handled by AgentService after execute() returns.
    }
}

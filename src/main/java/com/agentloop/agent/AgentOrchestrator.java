package com.agentloop.agent;

import com.agentloop.memory.ChatMemoryService;
import com.agentloop.rag.RetrievalService;
import com.agentloop.tools.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * State-machine-driven agent orchestrator.
 * <p>Replaces the inline loop in {@link com.agentloop.service.AgentService}.
 * Manages conversation state, tool scheduling, memory persistence, and metrics.</p>
 *
 * <h3>State flow</h3>
 * <pre>
 * INIT → THINKING ──→ TOOL_CALLING ──→ THINKING
 *            │                              │
 *            └──→ RESPONDING → FINISHED     │
 *                         ↓                 │
 *                      ERROR ────────→ FINISHED
 * </pre>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    // ═══════════════════════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════════════════════

    private final ChatClient knowledgeChatClient;
    private final ChatClient directChatClient;
    private final ChatMemoryService memoryService;
    private final RetrievalService retrievalService;
    private final RagTool ragTool;
    private final ResilientToolExecutor toolExecutor;
    private final AgentMetrics metrics;

    // Executor for the entire agent loop (one loop per submit)
    private final ExecutorService loopExecutor;

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════

    public AgentOrchestrator(
            RetrievalService retrievalService,
            ChatMemoryService memoryService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            ResilientToolExecutor toolExecutor,
            AgentMetrics metrics,
            RagTool ragTool,
            WebSearchTool webSearchTool,
            MathTool mathTool,
            FileReadTool fileReadTool,
            CurrentDateTool currentDateTool) {

        this.memoryService = memoryService;
        this.retrievalService = retrievalService;
        this.toolExecutor = toolExecutor;
        this.metrics = metrics;
        this.ragTool = ragTool;

        // ChatClient with full tool set (including RAG)
        this.knowledgeChatClient = chatClientBuilderProvider.getObject()
                .defaultSystem("""
                        You are a helpful AI agent, developed by JoeyLiu.

                        IMPORTANT RULES:
                        1. When answering questions about facts, information, or knowledge — use rag_query tool first to search the knowledge base
                        2. If you add documents to the knowledge base, ALWAYS verify with rag_query that they were stored correctly
                        3. For math, date, file questions — use the appropriate tool
                        4. If a tool returns results, cite them in your answer using [来源：xxx] format
                        5. NEVER make up information. Only answer based on tool results or explicitly provided facts
                        """)
                .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool, ragTool)
                .build();

        // ChatClient without RAG tools
        this.directChatClient = chatClientBuilderProvider.getObject()
                .defaultSystem("""
                        You are a helpful AI agent, developed by JoeyLiu.

                        IMPORTANT RULES:
                        1. Answer directly from your general knowledge unless the user explicitly provides facts in the prompt
                        2. For math, date, file questions — use the appropriate tool
                        3. Do not use or mention the local knowledge base in this mode
                        4. If a tool returns results, cite them in your answer using [来源：xxx] format
                        """)
                .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool)
                .build();

        // Dedicated executor for the agent loop (Java 17 compatible)
        this.loopExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-loop-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        log.info("AgentOrchestrator initialized");
    }

    private static final java.util.concurrent.atomic.AtomicInteger counter =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute the agent loop synchronously.
     * Blocks up to {@code ctx.totalTimeoutMs} via the internal executor.
     */
    public String execute(AgentContext ctx) {
        log.info("AgentOrchestrator.execute: sessionId={}, msgCount={}, traceId={}",
                ctx.getSessionId(), ctx.getMessages().size(), ctx.getTraceId());

        ctx.markLoopStart();

        try {
            Future<String> future = loopExecutor.submit(() -> runLoop(ctx));
            return future.get(ctx.getTotalTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent loop timed out after {}ms for session {}", ctx.getTotalTimeoutMs(), ctx.getSessionId());
            ctx.transitionTo(AgentLoopState.ERROR);
            return "[timeout] agent loop exceeded " + ctx.getTotalTimeoutMs() / 1000 + "s";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("Agent loop execution error: {}", cause != null ? cause.getMessage() : "unknown", cause);
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
    // Core loop (runs on virtual thread)
    // ═══════════════════════════════════════════════════════════════════════

    private String runLoop(AgentContext ctx) {
        Timer.Sample loopTimer = metrics.startLoopTimer();
        boolean success = false;

        try {
            // Step 0: INIT → THINKING
            ctx.transitionTo(AgentLoopState.THINKING);

            // Determine which ChatClient to use
            ChatClient chatClient = ctx.isUseKnowledgeBase() ? knowledgeChatClient : directChatClient;
            if (ctx.isUseKnowledgeBase()) {
                ragTool.setActiveKnowledgeBaseId(ctx.getKnowledgeBaseId());
            } else {
                ragTool.clearActiveKnowledgeBaseId();
            }

            // Load session history from memory
            loadSessionHistory(ctx);

            // Pre-populate with RAG context if applicable
            if (ctx.isUseKnowledgeBase()) {
                enrichWithRagContext(ctx);
            }

            metrics.updateContextSize(ctx.getMessages().size());

            // ── Main loop ──────────────────────────────────────────────────
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
                    log.debug("Step {}: calling LLM (state={})", ctx.getCurrentStep(), ctx.getState());
                    ChatResponse response = callLlm(chatClient, ctx);

                    if (response == null || response.getResult() == null) {
                        log.warn("Step {}: empty response from LLM", ctx.getCurrentStep());
                        ctx.transitionTo(AgentLoopState.ERROR);
                        break;
                    }

                    AssistantMessage assistantMessage = response.getResult().getOutput();

                    if (!assistantMessage.hasToolCalls()) {
                        // No tool calls → response is ready
                        String content = assistantMessage.getText();
                        log.info("Step {}: LLM responded directly ({} chars)", ctx.getCurrentStep(),
                                content != null ? content.length() : 0);

                        ctx.transitionTo(AgentLoopState.RESPONDING);

                        // Persist to memory
                        persistMessages(ctx);

                        success = true;
                        return content != null ? content : "";
                    }

                    // PHASE: TOOL_CALLING — execute tools with resilience
                    log.info("Step {}: {} tool call(s) requested", ctx.getCurrentStep(),
                            assistantMessage.getToolCalls().size());

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

            // ── Loop exhausted ─────────────────────────────────────────────
            if (!ctx.isTerminal() && ctx.getState() != AgentLoopState.RESPONDING) {
                log.warn("Agent loop exhausted after {} steps", ctx.getMaxSteps());
                return "[max_steps] reached limit (" + ctx.getMaxSteps() + ")";
            }

            success = true;
            return "[error] unexpected termination in state " + ctx.getState();

        } finally {
            metrics.stopLoopTimer(loopTimer);
            metrics.recordLoopEnd(success);
            ragTool.clearActiveKnowledgeBaseId();
            ctx.cleanup();
            log.info("Agent loop completed (success={}) for session {}", success, ctx.getSessionId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LLM call
    // ═══════════════════════════════════════════════════════════════════════

    private ChatResponse callLlm(ChatClient chatClient, AgentContext ctx) {
        try {
            return chatClient.prompt()
                    .messages(ctx.getMessages())
                    .advisors(new SimpleLoggerAdvisor())
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.error("LLM call failed at step {}: {}", ctx.getCurrentStep(), e.getMessage());
            throw e;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Memory helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void loadSessionHistory(AgentContext ctx) {
        List<ChatMemoryService.ChatMessage> history =
                memoryService.getRecentMessages(ctx.getSessionId(), 20);

        for (ChatMemoryService.ChatMessage chatMsg : history) {
            if ("user".equalsIgnoreCase(chatMsg.role())) {
                ctx.addMessage(new UserMessage(chatMsg.content()));
            } else if ("assistant".equalsIgnoreCase(chatMsg.role())) {
                ctx.addMessage(new AssistantMessage(chatMsg.content()));
            }
        }
        log.debug("Loaded {} historical messages for session {}", history.size(), ctx.getSessionId());
    }

    private void persistMessages(AgentContext ctx) {
        // The last user message and last assistant response are what we persist.
        // Since the context may contain many intermediate tool messages,
        // we extract only the original user query and the final response.
        // NOTE: in the current flow, the user message was already added to ctx
        // before calling execute(). The final response is returned to AgentService
        // which handles persistence.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RAG enrichment
    // ═══════════════════════════════════════════════════════════════════════

    private void enrichWithRagContext(AgentContext ctx) {
        // Find the last user message
        String userQuery = null;
        for (int i = ctx.getMessages().size() - 1; i >= 0; i--) {
            if (ctx.getMessages().get(i) instanceof UserMessage um) {
                userQuery = um.getText();
                break;
            }
        }
        if (userQuery == null) return;

        if (!isKnowledgeQuestion(userQuery)) return;

        try {
            var docs = retrievalService.retrieve(userQuery, 5, ctx.getKnowledgeBaseId());
            if (!docs.isEmpty()) {
                String ragResult = retrievalService.answerWithCitations(userQuery, docs);
                String enriched = String.format("""
                        You have access to a knowledge base. Here is the search result:
                        ---
                        %s
                        ---
                        IMPORTANT: Answer ONLY using the knowledge base result above.
                        If the result answers the question, respond with the answer and cite the source like [来源：xxx].
                        If the result does NOT answer the question, say "知识库中没有相关信息，我将用我的知识来回答".
                        Do NOT make up information.
                        User question: %s
                        """, ragResult, userQuery);
                // Replace the last user message with enriched version
                ctx.getMessages().remove(ctx.getMessages().size() - 1);
                ctx.addMessage(new UserMessage(enriched));
                log.info("Enriched user query with RAG context ({} docs)", docs.size());
            }
        } catch (Exception e) {
            log.warn("RAG enrichment failed: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Heuristics
    // ═══════════════════════════════════════════════════════════════════════

    private boolean isKnowledgeQuestion(String message) {
        String lower = message.toLowerCase().trim();
        if (lower.isEmpty()) return false;

        String[] skip = {"你是谁", "你是干什么", "你能做", "介绍一下你", "关于你",
                "你好", "hello", "hi", "thanks", "thank you", "再见", "bye"};
        for (String s : skip) {
            if (lower.startsWith(s) || lower.equals(s)) return false;
        }

        String[] keywords = {"是什么", "什么是", "是谁", "谁在", "查", "介绍", "解释",
                "原理", "概念", "查询", "如何", "怎么", "why", "what is", "how does", "explain"};
        for (String k : keywords) {
            if (lower.contains(k)) return true;
        }
        return false;
    }
}

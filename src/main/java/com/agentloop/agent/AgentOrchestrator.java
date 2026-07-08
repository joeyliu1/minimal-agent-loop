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
    private final RagTool ragTool;
    private final ResilientToolExecutor toolExecutor;
    private final AgentMetrics metrics;
    private final int llmRetries;

    // Executor for the entire agent loop (one loop per submit)
    private final ExecutorService loopExecutor;

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════

    public AgentOrchestrator(
            ChatMemoryService memoryService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            ResilientToolExecutor toolExecutor,
            AgentMetrics metrics,
            RagTool ragTool,
            WebSearchTool webSearchTool,
            MathTool mathTool,
            FileReadTool fileReadTool,
            CurrentDateTool currentDateTool,
            AgentProperties properties) {

        this.memoryService = memoryService;
        this.toolExecutor = toolExecutor;
        this.metrics = metrics;
        this.ragTool = ragTool;
        this.llmRetries = Math.max(1, properties.getLlmRetries());

        // ChatClient with full tool set (including RAG)
        this.knowledgeChatClient = chatClientBuilderProvider.getObject()
                .defaultSystem("""
                        你是一个「企业私有知识库智能助手」，专注于基于用户授权的本地知识库，提供有据可查的精准问答。

                        【定位】
                        - 核心价值是「准确」与「可溯源」：所有事实性结论都必须追溯到知识库文档或工具返回的真实内容。
                        - 你不是通用聊天机器人，而是帮助用户检索、理解、利用私有资料的专业助手。

                        【目标】
                        - 用最短路径给出准确答案，并清楚标注依据来源。
                        - 知识库未覆盖时诚实说明，绝不臆造或扩大结论。

                        【行为准则】
                        1. 先检索后回答：凡涉及事实、资料、文档、政策、流程的问题，必须先用 rag_query 检索相关片段，再基于命中内容作答。
                        2. 检索为空不编造：若 rag_query 无结果或信息不足，明确告知「知识库中未找到相关内容」，并建议补充资料或改用联网检索（web_search）。
                        3. 严格溯源：引用知识库内容用 [来源：知识库·文档名]；引用联网/工具结果用 [来源：web/工具名]；明确区分「知识库已有」与「外部获取」。
                        4. 工具分工：数学计算→math；日期时间→current_date；读取文件→file_read；实时/外部信息→web_search；知识库检索→rag_query。
                        5. 写入即校验：向知识库新增文档后，必须用 rag_query 验证是否成功入库。
                        6. 遇歧义先澄清：问题含糊（缺对象/时间/范围）时，先向用户确认关键信息再作答，不擅自假设。
                        7. 严谨区分置信度：清楚区分「知识库明确记载」「基于检索的合理推断」「个人通识补充」，避免用户误判可信度。
                        8. 复杂任务先拆解：多步或跨文档问题，先在脑中列出检索与推理步骤，再逐条执行，避免遗漏。
                        """)
                .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool, ragTool)
                .build();

        // ChatClient without RAG tools
        this.directChatClient = chatClientBuilderProvider.getObject()
                .defaultSystem("""
                        你是一个「通用智能助手」，基于自身通识能力直接回答用户问题，不依赖本地知识库。

                        【定位】
                        - 以「直接、准确、有条理」为原则，处理知识库模式之外的广泛问题。
                        - 本模式下本地知识库不可用，请勿提及或引用它。

                        【目标】
                        - 用清晰结构（要点 / 步骤 / 对比）给出可直接使用的答案。
                        - 需要实时或外部信息时，主动调用工具获取，而非凭空猜测。

                        【行为准则】
                        1. 直接作答：除非用户随问题提供了具体事实或文件，否则直接用通识回答，无需检索。
                        2. 工具分工：数学→math；日期→current_date；读文件→file_read；实时/外部信息→web_search。
                        3. 主动联网：涉及时效（新闻、价格、天气、最新政策）或不确定且可查证的外部信息时，使用 web_search 获取后再回答。
                        4. 引用标注：凡引用工具结果，用 [来源：web/工具名] 标注。
                        5. 复杂问题先规划：多步推理或复合问题，先简述解决思路，再分步执行并汇总。
                        6. 坦诚边界：不确定时明确说明「这部分我无法确定」，并给出推断的合理范围，不假装权威。
                        7. 简洁优先：结论前置，细节按需展开，避免冗余铺垫。
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
        log.info("AgentOrchestrator.execute: sessionId={}, traceId={}, useKb={}",
                ctx.getSessionId(), ctx.getTraceId(), ctx.isUseKnowledgeBase());

        ctx.markLoopStart();

        Future<String> future = loopExecutor.submit(() -> runLoop(ctx));
        try {
            return future.get(ctx.getTotalTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent loop timed out after {}ms for session {}", ctx.getTotalTimeoutMs(), ctx.getSessionId());
            ctx.transitionTo(AgentLoopState.ERROR);
            // Best effort cancellation. HTTP callers still receive the timeout response immediately.
            future.cancel(true);
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
    // Core loop
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

            // Load session history from memory (inserted BEFORE the current user message)
            int historySize = loadSessionHistory(ctx);
            log.info("AgentOrchestrator.runLoop: sessionId={}, historySize={}, totalMsgs={}",
                    ctx.getSessionId(), historySize, ctx.getMessages().size());

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
        Exception lastError = null;
        for (int attempt = 1; attempt <= llmRetries; attempt++) {
            try {
                if (attempt > 1) {
                    long delayMs = 500L * (1L << (attempt - 2));
                    log.info("Retry {}/{} for LLM call after {}ms", attempt, llmRetries, delayMs);
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
                llmRetries, ctx.getCurrentStep(), lastError != null ? lastError.getMessage() : "unknown");
        throw new RuntimeException(lastError != null ? lastError.getMessage() : "LLM call failed", lastError);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Memory helpers
    // ═══════════════════════════════════════════════════════════════════════

    private int loadSessionHistory(AgentContext ctx) {
        List<ChatMemoryService.ChatMessage> history =
                memoryService.getRecentMessages(ctx.getSessionId(), 20);

        // Build history list (oldest first, matching DB order)
        List<Message> histMsgs = new ArrayList<>();
        for (ChatMemoryService.ChatMessage chatMsg : history) {
            if ("user".equalsIgnoreCase(chatMsg.role())) {
                histMsgs.add(new UserMessage(chatMsg.content()));
            } else if ("assistant".equalsIgnoreCase(chatMsg.role())) {
                histMsgs.add(new AssistantMessage(chatMsg.content()));
            }
        }
        // The current user message was pre-added by AgentService at index 0.
        // Insert history BEFORE it so the final order is [history..., currentUser].
        ctx.getMessages().addAll(0, histMsgs);
        log.debug("Loaded {} historical messages for session {}", history.size(), ctx.getSessionId());
        return history.size();
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
    // RAG retrieval is now delegated to the rag_query tool (registered on
    // knowledgeChatClient). The agent loop no longer pre-fetches RAG context,
    // eliminating the dual RAG path and the stale-query bug in enrichment.
    // See RagTool / RetrievalService.
    // ═══════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // Heuristics
    // ═══════════════════════════════════════════════════════════════════════
}

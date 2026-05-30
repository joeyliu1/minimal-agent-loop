package com.agentloop.service;

import com.agentloop.config.AgentProperties;
import com.agentloop.memory.ChatMemoryService;
import com.agentloop.memory.ChatMemoryService.ChatMessage;
import com.agentloop.rag.RetrievalService;
import com.agentloop.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;

/**
 * Agent execution engine.
 * Each session has its own ChatMemory (backed by MySQL).
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final RetrievalService retrievalService;
    private final ChatMemoryService memoryService;
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentService(
                RetrievalService retrievalService,
                ChatMemoryService memoryService,
                ChatClient.Builder chatClientBuilder,
                WebSearchTool webSearchTool,
                MathTool mathTool,
                FileReadTool fileReadTool,
                CurrentDateTool currentDateTool,
                RagTool ragTool,
                AgentProperties properties
        ) {
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.chatClientBuilder = chatClientBuilder
                .defaultSystem("""
                    You are a helpful AI agent, developed by JoeyLiu.

                    IMPORTANT RULES:
                    1. When answering questions about facts, information, or knowledge — use rag_query tool first to search the knowledge base
                    2. If you add documents to the knowledge base, ALWAYS verify with rag_query that they were stored correctly
                    3. For math, date, file questions — use the appropriate tool
                    4. If a tool returns results, cite them in your answer using [来源: xxx] format
                    5. NEVER make up information. Only answer based on tool results or explicitly provided facts
                    """)
                .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool, ragTool)
                .build();
        this.maxSteps = properties.getMaxSteps();
        this.timeoutSeconds = properties.getTimeoutSeconds();
        log.info("AgentService initialized: maxSteps={}, timeout={}s", maxSteps, timeoutSeconds);
    }

    /**
     * Execute the agent loop for a given session with a user message.
     * Block up to `timeoutSeconds`.
     */
    public String execute(String sessionId, String userMessage) {
        log.info("AgentService.execute session={} message={}", sessionId, userMessage);
        try {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<String> future = exec.submit(() -> loop(sessionId, userMessage));
                String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                return result;
            } finally {
                exec.shutdownNow();
            }
        } catch (TimeoutException e) {
            log.warn("Agent loop timeout after {}s", timeoutSeconds);
            return "[timeout] exceeded " + timeoutSeconds + "s";
        } catch (Exception e) {
            log.error("Agent loop error: {}", e.getMessage(), e);
            return "[error] " + e.getMessage();
        }
    }

    private String loop(String sessionId, String userMessage) {
        // Load session history for context
        List<ChatMessage> history = memoryService.getRecentMessages(sessionId);

        // Detect knowledge question
        String ragResult = null;
        boolean isKnowledge = isKnowledgeQuestion(userMessage);
        if (isKnowledge) {
            try {
                ragResult = CompletableFuture.supplyAsync(() -> retrievalService.ragAnswer(userMessage, 3))
                        .get(30, TimeUnit.SECONDS);
                log.info("RAG result: {}", ragResult);
            } catch (TimeoutException e) {
                log.warn("RAG query timed out after 30s, proceeding without knowledge base");
            } catch (Exception e) {
                log.error("RAG query failed: {}", e.getMessage());
            }
        }

        for (int step = 0; step < maxSteps; step++) {
            try {
                String content;

                if (ragResult != null && step == 0) {
                    String ragLower = ragResult.toLowerCase();
                    boolean ragHasContent = !ragLower.contains("没有相关信息") && !ragLower.contains("no relevant");

                    if (ragHasContent) {
                        content = ChatClient.builder()
                                .build()
                                .prompt()
                                .user(String.format("""
                                    You have access to a knowledge base. Here is the search result:

                                    ---
                                    %s
                                    ---

                                    IMPORTANT: Answer ONLY using the knowledge base result above.
                                    If the result answers the question, respond with the answer and cite the source like [来源: xxx].
                                    If the result does NOT answer the question, say "知识库中没有相关信息，我将用我的知识来回答"。
                                    Do NOT make up information.

                                    User question: %s
                                    """, ragResult, userMessage))
                                .advisors(new SimpleLoggerAdvisor())
                                .call()
                                .content();
                    } else {
                        content = null;
                    }
                    ragResult = null;
                } else {
                    // Build prompt with history
                    var promptBuilder = ChatClient.builder()
                            .build()
                            .prompt();

                    // Re-play history
                    for (ChatMessage msg : history) {
                        if ("user".equals(msg.role())) {
                            promptBuilder.user(msg.content());
                        } else {
                            promptBuilder.user(msg.content()); // assistant goes as user msg in turns
                        }
                    }
                    // Current message
                    promptBuilder.user(userMessage);

                    content = promptBuilder
                            .advisors(new SimpleLoggerAdvisor())
                            .call()
                            .content();
                }

                if (content != null && !content.isBlank()) {
                    log.info("Agent response: {}", content);
                    return content;
                }
            } catch (Exception e) {
                log.error("Step {} error: {}", step, e.getMessage());
                return "[error] " + e.getMessage();
            }
        }
        return "[max_steps] reached limit (" + maxSteps + ")";
    }

    private boolean isKnowledgeQuestion(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("你是谁") || lower.contains("你是干什么") || lower.contains("你能做")
                || lower.contains("介绍一下你") || lower.contains("关于你")) {
            return false;
        }
        return lower.contains("是什么") || lower.contains("什么是")
                || lower.contains("是谁") || lower.contains("谁在") || lower.contains("查")
                || lower.contains("介绍") || lower.contains("解释")
                || lower.contains("原理") || lower.contains("概念")
                || lower.contains("查一下") || lower.contains("查询")
                || (message.length() < 50 && message.contains("?"));
    }
}
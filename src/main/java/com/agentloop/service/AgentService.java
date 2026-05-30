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

    private final ChatClient chatClient;
    private final RetrievalService retrievalService;
    private final ChatMemoryService memoryService;
    private final int maxSteps;
    private final long timeoutSeconds;
    private final ExecutorService agentExecutor = Executors.newCachedThreadPool();

    public AgentService(
                RetrievalService retrievalService,
                ChatMemoryService memoryService,
                ChatClient chatClient,
                WebSearchTool webSearchTool,
                MathTool mathTool,
                FileReadTool fileReadTool,
                CurrentDateTool currentDateTool,
                RagTool ragTool,
                AgentProperties properties
        ) {
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.chatClient = chatClient;
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
            Future<String> future = agentExecutor.submit(() -> loop(sessionId, userMessage));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
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

        // Always retrieve from knowledge base first — use similarity score to decide
        String ragResult = null;
        try {
            var docs = CompletableFuture.supplyAsync(
                    () -> retrievalService.retrieveWithScore(userMessage, 3))
                .get(30, TimeUnit.SECONDS);
            // Use result only if score is high enough (> 0.5), otherwise skip RAG
            if (docs != null && !docs.isEmpty() && docs.get(0).score() > 0.5f) {
                ragResult = retrievalService.answerFromScoredDocs(userMessage, docs);
                log.info("RAG result: {}", ragResult);
            } else {
                log.info("Low similarity score, skipping RAG");
            }
        } catch (TimeoutException e) {
            log.warn("RAG query timed out after 30s, proceeding without knowledge base");
        } catch (Exception e) {
            log.error("RAG query failed: {}", e.getMessage());
        }

        for (int step = 0; step < maxSteps; step++) {
            try {
                String content;

                if (ragResult != null && step == 0) {
                    String ragLower = ragResult.toLowerCase();
                    boolean ragHasContent = !ragLower.contains("没有相关信息") && !ragLower.contains("no relevant");

                    if (ragHasContent) {
                        content = chatClient.prompt()
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
                    var promptBuilder = chatClient.prompt();

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

}
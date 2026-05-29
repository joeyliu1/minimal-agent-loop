package com.agentloop.service;

import com.agentloop.config.AgentProperties;
import com.agentloop.rag.RetrievalService;
import com.agentloop.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/**
 * Agent execution engine.
 * Holds a ChatClient wired with tools, runs the agent loop with timeout.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient chatClient;
    private final RetrievalService retrievalService;
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentService(
                RetrievalService retrievalService,
                ChatClient.Builder chatClientBuilder,
                WebSearchTool webSearchTool,
                MathTool mathTool,
                FileReadTool fileReadTool,
                CurrentDateTool currentDateTool,
                RagTool ragTool,
                AgentProperties properties
        ) {
            this.retrievalService = retrievalService;
            this.chatClient = chatClientBuilder
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
     * Execute the agent loop with a user message.
     * Blocks up to `timeoutSeconds`.
     */
    public String execute(String userMessage) {
        log.info("AgentService.execute called with: {}", userMessage);
        try {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<String> future = exec.submit(() -> loop(userMessage));
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            exec.shutdownNow();
            return result;
        } catch (TimeoutException e) {
            log.warn("Agent loop timeout after {}s", timeoutSeconds);
            return "[timeout] exceeded " + timeoutSeconds + "s";
        } catch (Exception e) {
            log.error("Agent loop error: {}", e.getMessage(), e);
            return "[error] " + e.getMessage();
        }
    }

    private String loop(String userMessage) {
        // For knowledge-based questions, automatically query knowledge base first
        String ragResult = null;
        boolean isKnowledgeQuestion = isKnowledgeQuestion(userMessage);

        if (isKnowledgeQuestion) {
            log.info("Detected knowledge question, querying RAG first...");
            try {
                ragResult = retrievalService.ragAnswer(userMessage, 3);
                log.info("RAG result: {}", ragResult);
            } catch (Exception e) {
                log.error("RAG query failed: {}", e.getMessage());
            }
        }

        for (int step = 0; step < maxSteps; step++) {
            log.debug("Agent loop step {}", step);
            try {
                String content;
                if (ragResult != null && step == 0) {
                    String ragLower = ragResult.toLowerCase();
                    boolean ragHasContent = !ragLower.contains("没有相关信息") && !ragLower.contains("no relevant");
                    
                    if (ragHasContent) {
                        // RAG found relevant content, use it
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
                        // RAG found nothing, fall back to normal LLM
                        log.info("RAG found no relevant content, falling back to LLM...");
                        content = null; // force next step to use LLM
                    }
                    ragResult = null;
                } else {
                    content = chatClient.prompt()
                            .user(userMessage)
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

    /**
     * Heuristic: detect if user is asking a knowledge-based question that should use RAG.
     */
    private boolean isKnowledgeQuestion(String message) {
        String lower = message.toLowerCase();
        // Questions about the agent itself should NOT use RAG
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
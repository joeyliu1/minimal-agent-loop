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
                    // First step: answer based on RAG result, no tool call needed
                    content = chatClient.prompt()
                            .user(String.format("""
                                    Based on this knowledge base search result:
                                    %s

                                    Answer the user's question in Chinese, keeping the answer concise and accurate.
                                    If the result is relevant, use the information and cite the source.
                                    If not relevant, say you don't know.
                                    User question: %s
                                    """, ragResult, userMessage))
                            .advisors(new SimpleLoggerAdvisor())
                            .call()
                            .content();
                    ragResult = null; // Only use RAG result on first step
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
        return lower.contains("是什么") || lower.contains("什么是")
                || lower.contains("介绍") || lower.contains("解释")
                || lower.contains("原理") || lower.contains("概念")
                || lower.contains("查一下") || lower.contains("查询")
                || (message.length() < 50 && message.contains("?"));
    }
}
package com.agentloop.service;

import com.agentloop.config.AgentProperties;
import com.agentloop.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentService(
            ChatClient.Builder chatClientBuilder,
            WebSearchTool webSearchTool,
            MathTool mathTool,
            FileReadTool fileReadTool,
            CurrentDateTool currentDateTool,
            RagTool ragTool,
            AgentProperties properties
    ) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                    You are a helpful AI agent, developed by JoeyLiu.

                    IMPORTANT RULES:
                    1. When answering questions about facts, information, or knowledge — ALWAYS use the rag_query tool first to search the knowledge base
                    2. If you add documents to the knowledge base, ALWAYS verify with rag_query that they were stored correctly
                    3. For math, date, file questions — use the appropriate tool
                    4. If a tool returns results, cite them in your answer using [来源: xxx] format
                    5. NEVER make up information. Only answer based on tool results or explicitly provided facts

                    Workflow for knowledge questions:
                    1. Call rag_query to search the knowledge base
                    2. If results are found, answer ONLY using that content and cite the source
                    3. If no results, say "知识库中没有相关信息"
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
        for (int step = 0; step < maxSteps; step++) {
            log.debug("Agent loop step {}", step);
            try {
                String content = chatClient.prompt()
                        .user(userMessage)
                        .advisors(new SimpleLoggerAdvisor())
                        .call()
                        .content();

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
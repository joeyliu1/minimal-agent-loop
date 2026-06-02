package com.agentloop.service;

import com.agentloop.config.AgentProperties;
import com.agentloop.rag.RetrievalService;
import com.agentloop.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Agent execution engine.
 * Holds a ChatClient wired with tools, runs the agent loop with timeout.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient knowledgeChatClient;
    private final ChatClient directChatClient;
    private final RetrievalService retrievalService;
    private final ToolCallingManager toolCallingManager;
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentService(
                RetrievalService retrievalService,
                ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                WebSearchTool webSearchTool,
                MathTool mathTool,
                FileReadTool fileReadTool,
                CurrentDateTool currentDateTool,
                RagTool ragTool,
                AgentProperties properties
        ) {
            this.retrievalService = retrievalService;
            this.toolCallingManager = DefaultToolCallingManager.builder().build();
            this.knowledgeChatClient = chatClientBuilderProvider.getObject()
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
            this.directChatClient = chatClientBuilderProvider.getObject()
                    .defaultSystem("""
                    You are a helpful AI agent, developed by JoeyLiu.

                    IMPORTANT RULES:
                    1. Answer directly from your general knowledge unless the user explicitly provides facts in the prompt
                    2. For math, date, file questions — use the appropriate tool
                    3. Do not use or mention the local knowledge base in this mode
                    4. If a tool returns results, cite them in your answer using [来源: xxx] format
                    """)
                    .defaultTools(webSearchTool, mathTool, fileReadTool, currentDateTool)
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
        return execute(userMessage, true);
    }

    public String execute(String userMessage, boolean useKnowledgeBase) {
        log.info("AgentService.execute called with: {}, useKnowledgeBase={}", userMessage, useKnowledgeBase);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = exec.submit(() -> loop(userMessage, useKnowledgeBase));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent loop timeout after {}s", timeoutSeconds);
            return "[timeout] exceeded " + timeoutSeconds + "s";
        } catch (Exception e) {
            log.error("Agent loop error: {}", e.getMessage(), e);
            return "[error] " + e.getMessage();
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Real agent loop: build the initial user message, then iterate.
     * Each iteration sends the full conversation history to the model.
     * If the model requests tool calls, execute them and append the results
     * to the history; otherwise return the final assistant content.
     */
    private String loop(String userMessage, boolean useKnowledgeBase) {
        ChatClient activeChatClient = useKnowledgeBase ? knowledgeChatClient : directChatClient;

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(buildInitialUserContent(userMessage, useKnowledgeBase)));

        for (int step = 0; step < maxSteps; step++) {
            log.debug("Agent loop step {}", step);
            try {
                ChatResponse response = activeChatClient.prompt()
                        .messages(messages)
                        .advisors(new SimpleLoggerAdvisor())
                        .call()
                        .chatResponse();

                if (response == null || response.getResult() == null) {
                    log.warn("Step {}: empty response from model", step);
                    return "[error] empty response from model";
                }

                AssistantMessage assistantMessage = response.getResult().getOutput();

                if (!assistantMessage.hasToolCalls()) {
                    String content = assistantMessage.getText();
                    log.info("Agent response (step {}): {}", step, content);
                    return content != null ? content : "";
                }

                log.info("Step {}: {} tool call(s) requested", step, assistantMessage.getToolCalls().size());
                messages.add(assistantMessage);

                Prompt promptWithAssistant = new Prompt(messages);
                var result = toolCallingManager.executeToolCalls(promptWithAssistant, response);
                if (result != null && result.conversationHistory() != null) {
                    messages.addAll(result.conversationHistory());
                }
            } catch (Exception e) {
                log.error("Step {} error: {}", step, e.getMessage(), e);
                return "[error] " + e.getMessage();
            }
        }
        return "[max_steps] reached limit (" + maxSteps + ")";
    }

    /**
     * Build the first user message, optionally prepending RAG context.
     */
    private String buildInitialUserContent(String userMessage, boolean useKnowledgeBase) {
        if (!useKnowledgeBase || !isKnowledgeQuestion(userMessage)) {
            return userMessage;
        }

        log.info("Detected knowledge question, querying RAG first...");
        List<RetrievalService.RetrievedDocument> docs;
        try {
            docs = retrievalService.retrieve(userMessage, 5);
        } catch (Exception e) {
            log.error("RAG query failed: {}", e.getMessage());
            return userMessage;
        }
        if (docs.isEmpty()) {
            log.info("RAG found no relevant documents, falling back to LLM...");
            return userMessage;
        }

        String ragResult = retrievalService.answerWithCitations(userMessage, docs);
        log.info("RAG answer: {}", ragResult);

        return String.format("""
            You have access to a knowledge base. Here is the search result:

            ---
            %s
            ---

            IMPORTANT: Answer ONLY using the knowledge base result above.
            If the result answers the question, respond with the answer and cite the source like [来源: xxx].
            If the result does NOT answer the question, say "知识库中没有相关信息，我将用我的知识来回答".
            Do NOT make up information.

            User question: %s
            """, ragResult, userMessage);
    }

    /**
     * Heuristic: detect if user is asking a knowledge-based question that should use RAG.
     * Skips greetings, self-intent questions, and short chit-chat.
     */
    private boolean isKnowledgeQuestion(String message) {
        String lower = message.toLowerCase().trim();
        if (lower.isEmpty()) return false;

        // Self-intent / chit-chat → never RAG
        String[] skipPatterns = {
                "你是谁", "你是干什么", "你能做", "介绍一下你", "关于你",
                "你好", "hello", "hi", "thanks", "thank you", "再见", "bye"
        };
        for (String p : skipPatterns) {
            if (lower.startsWith(p) || lower.equals(p)) return false;
        }

        // Explicit knowledge-seeking keywords
        String[] knowledgeKeywords = {
                "是什么", "什么是", "是谁", "谁在", "查", "介绍", "解释",
                "原理", "概念", "查询", "如何", "怎么", "why", "what is", "how does", "explain"
        };
        for (String k : knowledgeKeywords) {
            if (lower.contains(k)) return true;
        }
        return false;
    }
}

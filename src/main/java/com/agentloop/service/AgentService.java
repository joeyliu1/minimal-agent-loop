package com.agentloop.service;

import com.agentloop.config.AgentProperties;
import com.agentloop.memory.ChatMemoryService;
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
import java.util.stream.Collectors;

/**
 * Optimized Agent execution engine with parallel tool calling support.
 * Uses a reusable thread pool for better performance, with proper timeout handling.
 * When LLM requests multiple tool calls in one step, they are executed concurrently.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    // Reusable thread pool for agent loop - only one executor shared across all requests
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            1, 5,      // 最小 1 个，最大 5 个线程
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("agent-loop-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    
    // Dedicated thread pool for parallel tool execution (max 10 concurrent tools)
    private static final ExecutorService TOOL_EXECUTOR = new ThreadPoolExecutor(
            2, 10,     // 最小 2 个，最大 10 个线程
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("tool-exec-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final ChatClient knowledgeChatClient;
    private final ChatClient directChatClient;
    private final RetrievalService retrievalService;
    private final RagTool ragTool;
    private final ToolCallingManager toolCallingManager;
    private final ChatMemoryService memoryService;
    private final int maxSteps;
    private final long timeoutSeconds;

    public AgentService(
            RetrievalService retrievalService,
            ChatMemoryService memoryService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            WebSearchTool webSearchTool,
            MathTool mathTool,
            FileReadTool fileReadTool,
            CurrentDateTool currentDateTool,
            RagTool ragTool,
            AgentProperties properties
    ) {
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.ragTool = ragTool;
        this.toolCallingManager = DefaultToolCallingManager.builder().build();
        
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
                
        this.maxSteps = properties.getMaxSteps();
        this.timeoutSeconds = properties.getTimeoutSeconds();
        log.info("AgentService initialized: maxSteps={}, timeout={}s, PARALLEL_TOOL_EXECUTION=enabled", maxSteps, timeoutSeconds);
    }

    /**
     * Execute the agent loop with a user message.
     * Blocks up to `timeoutSeconds` using a shared thread pool.
     */
    public String execute(String userMessage) {
        return execute(userMessage, true, null);
    }

    public String execute(String userMessage, boolean useKnowledgeBase) {
        return execute(userMessage, useKnowledgeBase, null);
    }

    public String execute(String userMessage, boolean useKnowledgeBase, String knowledgeBaseId) {
        return execute(userMessage, useKnowledgeBase, knowledgeBaseId, "default-session-" + System.currentTimeMillis());
    }

    public String execute(String userMessage, boolean useKnowledgeBase, String knowledgeBaseId, String sessionId) {
        log.info("AgentService.execute called with: {}, useKnowledgeBase={}, knowledgeBaseId={}, sessionId={}",
                userMessage, useKnowledgeBase, knowledgeBaseId, sessionId);
        
        long startTime = System.currentTimeMillis();
        
        try {
            Future<String> future = EXECUTOR.submit(() -> 
                loop(userMessage, useKnowledgeBase, knowledgeBaseId, sessionId)
            );
            
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
            
        } catch (TimeoutException e) {
            log.warn("Agent loop timeout after {}s for session {}", timeoutSeconds, sessionId);
            return "[timeout] exceeded " + timeoutSeconds + "s";
            
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException rtEx) {
                throw rtEx;
            }
            log.error("Agent loop execution error: {}", cause.getMessage(), cause);
            return "[error] " + cause.getMessage();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Agent loop interrupted");
            return "[interrupted] request cancelled";
            
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("AgentService.execute completed in {}ms", elapsed);
        }
    }

    /**
     * Real agent loop: build the initial user message, then iterate.
     * Each iteration sends the full conversation history to the model.
     * If the model requests tool calls, execute them IN PARALLEL and append the results.
     */
    private String loop(String userMessage, boolean useKnowledgeBase, String knowledgeBaseId, String sessionId) {
        ChatClient activeChatClient = useKnowledgeBase ? knowledgeChatClient : directChatClient;
        if (useKnowledgeBase) {
            ragTool.setActiveKnowledgeBaseId(knowledgeBaseId);
        } else {
            ragTool.clearActiveKnowledgeBaseId();
        }

        // Load recent conversation history from memory
        List<Message> messages = new ArrayList<>();
        List<ChatMemoryService.ChatMessage> history = memoryService.getRecentMessages(sessionId, 20);
        for (ChatMemoryService.ChatMessage chatMsg : history) {
            if ("user".equalsIgnoreCase(chatMsg.role())) {
                messages.add(new UserMessage(chatMsg.content()));
            } else if ("assistant".equalsIgnoreCase(chatMsg.role())) {
                messages.add(new AssistantMessage(chatMsg.content()));
            }
        }
        
        messages.add(new UserMessage(buildInitialUserContent(userMessage, useKnowledgeBase, knowledgeBaseId)));
        log.info("Loaded {} messages from memory for session {}", history.size(), sessionId);

        try {
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
                        memoryService.addMessage(sessionId, "user", userMessage);
                        memoryService.addMessage(sessionId, "assistant", content);
                        return content != null ? content : "";
                    }

                    int toolCallCount = assistantMessage.getToolCalls().size();
                    log.info("Step {}: {} tool call(s) requested", step, toolCallCount);
                    messages.add(assistantMessage);

                    // ★ OPTIMIZATION: Execute tool calls in PARALLEL
                    Prompt promptWithAssistant = new Prompt(messages);
                    executeToolCallsInParallel(promptWithAssistant, response, assistantMessage, messages);
                    
                } catch (Exception e) {
                    log.error("Step {} error: {}", step, e.getMessage(), e);
                    return "[error] " + e.getMessage();
                }
            }
            return "[max_steps] reached limit (" + maxSteps + ")";
        } finally {
            ragTool.clearActiveKnowledgeBaseId();
        }
    }

    /**
     * Execute multiple tool calls in PARALLEL for better performance.
     * When LLM requests N tool calls in one step, they are executed concurrently,
     * reducing total response time by up to N times.
     * 
     * Results are appended directly to the `messages` list.
     */
    private void executeToolCallsInParallel(Prompt prompt, ChatResponse response, 
                                           AssistantMessage assistantMessage, List<Message> messages) {
        var toolCalls = assistantMessage.getToolCalls();
        int toolCallCount = toolCalls.size();
        
        if (toolCallCount == 0) return;
        
        log.info("Executing {} tool call(s) in PARALLEL...", toolCallCount);
        long execStart = System.currentTimeMillis();
        
        // Create tasks for parallel execution
        List<Callable<List<Message>>> tasks = toolCalls.stream()
            .map(toolCall -> (Callable<List<Message>>) () -> {
                try {
                    String toolName = toolCall.name();
                    String args = truncate(toolCall.arguments().toString(), 80);
                    log.info("  → Executing tool: {}({})", toolName, args);
                    
                    // Execute this tool call
                    var singleResult = toolCallingManager.executeToolCalls(
                        new Prompt(List.of(assistantMessage)), 
                        response
                    );
                    
                    List<Message> toolMessages = new ArrayList<>();
                    if (singleResult != null && singleResult.conversationHistory() != null) {
                        toolMessages.addAll(singleResult.conversationHistory());
                    }
                    
                    log.info("  ← Tool {} completed ({} messages)", toolName, toolMessages.size());
                    return toolMessages;
                    
                } catch (Exception e) {
                    log.error("  ✗ Tool {} failed: {}", toolCall.name(), e.getMessage());
                    return new ArrayList<>(); // Return empty list on error
                }
            })
            .collect(Collectors.toList());
        
        // Execute all tasks in parallel with timeout
        List<Future<List<Message>>> futures;
        try {
            futures = TOOL_EXECUTOR.invokeAll(tasks, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Parallel tool execution interrupted");
            throw new RuntimeException("Tool execution interrupted", e);
        }
        
        // Collect results from all parallel executions
        int totalNewMessages = 0;
        for (var future : futures) {
            try {
                List<Message> toolMessages = future.get(5, TimeUnit.SECONDS);
                if (toolMessages != null && !toolMessages.isEmpty()) {
                    messages.addAll(toolMessages);
                    totalNewMessages += toolMessages.size();
                }
            } catch (Exception e) {
                log.warn("Failed to collect tool result: {}", e.getMessage());
            }
        }
        
        long execTime = System.currentTimeMillis() - execStart;
        if (toolCallCount > 1) {
            log.info("Parallel tool execution completed: {} new messages in {}ms (~{}x speedup estimated)", 
                totalNewMessages, execTime, toolCallCount);
        } else {
            log.info("Tool execution completed: {} new messages in {}ms", totalNewMessages, execTime);
        }
    }
    
    /**
     * Truncate a string to maxLen characters for logging.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Build the first user message, optionally prepending RAG context.
     */
    private String buildInitialUserContent(String userMessage, boolean useKnowledgeBase, String knowledgeBaseId) {
        if (!useKnowledgeBase || !isKnowledgeQuestion(userMessage)) {
            return userMessage;
        }

        log.info("Detected knowledge question, querying RAG first...");
        List<RetrievalService.RetrievedDocument> docs;
        try {
            docs = retrievalService.retrieve(userMessage, 5, knowledgeBaseId);
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
                If the result answers the question, respond with the answer and cite the source like [来源：xxx].
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

        String[] skipPatterns = {
                "你是谁", "你是干什么", "你能做", "介绍一下你", "关于你",
                "你好", "hello", "hi", "thanks", "thank you", "再见", "bye"
        };
        for (String p : skipPatterns) {
            if (lower.startsWith(p) || lower.equals(p)) return false;
        }

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

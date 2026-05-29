package com.agentloop.memory;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Short-term memory: conversation context within a session.
 * Uses a sliding window to keep recent messages.
 */
@Service
public class ChatMemoryService {

    private static final int DEFAULT_WINDOW_SIZE = 20;

    private final int windowSize;
    private final Map<String, LinkedList<ChatMessage>> sessionMemories = new ConcurrentHashMap<>();

    public ChatMemoryService() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public ChatMemoryService(int windowSize) {
        this.windowSize = windowSize;
    }

    /**
     * Add a message to session memory.
     */
    public void addMessage(String sessionId, String role, String content) {
        sessionMemories.computeIfAbsent(sessionId, k -> new LinkedList<>())
                .addLast(new ChatMessage(role, content));
        trimWindow(sessionId);
    }

    /**
     * Get recent messages for a session.
     */
    public List<ChatMessage> getRecentMessages(String sessionId) {
        return new ArrayList<>(sessionMemories.getOrDefault(sessionId, new LinkedList<>()));
    }

    /**
     * Get recent N messages.
     */
    public List<ChatMessage> getRecentMessages(String sessionId, int count) {
        LinkedList<ChatMessage> messages = sessionMemories.getOrDefault(sessionId, new LinkedList<>());
        int size = messages.size();
        if (count >= size) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(size - count, size));
    }

    /**
     * Clear session memory.
     */
    public void clearSession(String sessionId) {
        sessionMemories.remove(sessionId);
    }

    /**
     * Clear all memories.
     */
    public void clearAll() {
        sessionMemories.clear();
    }

    /**
     * Get session count.
     */
    public int getSessionCount() {
        return sessionMemories.size();
    }

    private void trimWindow(String sessionId) {
        LinkedList<ChatMessage> messages = sessionMemories.get(sessionId);
        if (messages != null && messages.size() > windowSize) {
            while (messages.size() > windowSize) {
                messages.removeFirst();
            }
        }
    }

    /**
     * Chat message record.
     */
    public record ChatMessage(String role, String content) {}
}
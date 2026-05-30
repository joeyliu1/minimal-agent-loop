package com.agentloop.memory;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Short-term memory: conversation context within a session.
 * Uses MySQL to persist chat history across restarts.
 */
@Service
public class ChatMemoryService {

    private final JdbcTemplate jdbc;
    private static final int DEFAULT_WINDOW_SIZE = 20;

    public ChatMemoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("SELECT 1 FROM chat_messages WHERE 1=0");
    }

    /**
     * Add a message to session memory.
     */
    public void addMessage(String sessionId, String role, String content) {
        jdbc.update(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)",
            sessionId, role, content
        );
    }

    /**
     * Get recent messages for a session.
     */
    public List<ChatMessage> getRecentMessages(String sessionId) {
        return getRecentMessages(sessionId, DEFAULT_WINDOW_SIZE);
    }

    /**
         * Get recent N messages in chronological order (oldest first — for ChatClient).
         */
        public List<ChatMessage> getRecentMessages(String sessionId, int count) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC LIMIT ?",
                sessionId, count
            );
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                messages.add(new ChatMessage((String) row.get("role"), (String) row.get("content")));
            }
            return messages;
        }

    /**
     * Clear session memory.
     */
    public void clearSession(String sessionId) {
        jdbc.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
    }

    /**
     * Clear all memories.
     */
    public void clearAll() {
        jdbc.update("DELETE FROM chat_messages");
    }

    /**
     * Get session count.
     */
    public int getSessionCount() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT session_id) FROM chat_messages", Integer.class
        );
        return count != null ? count : 0;
    }

    /**
     * Chat message record.
     */
    public record ChatMessage(String role, String content) {}
}
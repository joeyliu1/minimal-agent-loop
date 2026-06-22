package com.agentloop.memory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private final JdbcTemplate jdbc;
    private static final int DEFAULT_WINDOW_SIZE = 20;

    public ChatMemoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id VARCHAR(255) NOT NULL,
                role VARCHAR(10) NOT NULL COMMENT 'user or assistant',
                content TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_session (session_id),
                INDEX idx_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        try {
            jdbc.execute(createTableSQL);
            log.info("Chat memory table initialized successfully");
        } catch (Exception e) {
            // Table might already exist, which is fine
            if (e.getMessage() != null && !e.getMessage().contains("already exists") 
                    && !e.getMessage().contains("Duplicate column")) {
                log.error("Failed to initialize chat memory table: {}", e.getMessage(), e);
            } else {
                log.debug("Chat memory table already exists");
            }
        }
        
        // Verify the table is accessible
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class);
            log.info("Chat messages table ready, current row count: {}", count);
        } catch (Exception e) {
            log.warn("Could not verify chat messages table: {}", e.getMessage());
        }
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
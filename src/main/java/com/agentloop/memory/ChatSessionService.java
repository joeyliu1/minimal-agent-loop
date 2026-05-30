package com.agentloop.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages chat sessions: create, list, rename, delete.
 * Each aside-button click → new session + auto-send.
 */
@Service
public class ChatSessionService {

    private final JdbcTemplate jdbc;

    public ChatSessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Session CRUD ──────────────────────────────────────────────

    public Session createSession() {
        return createSession("新对话");
    }

    public Session createSession(String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_sessions (session_id, title) VALUES (?, ?)", id, title);
        return new Session(id, title, new Date(), new Date());
    }

    public List<Session> listSessions() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT session_id, title, created_at, updated_at FROM chat_sessions ORDER BY updated_at DESC"
        );
        return rows.stream().map(r -> new Session(
            (String) r.get("session_id"),
            (String) r.get("title"),
            ((java.sql.Timestamp) r.get("created_at")),
            ((java.sql.Timestamp) r.get("updated_at"))
        )).collect(Collectors.toList());
    }

    public void renameSession(String sessionId, String title) {
        jdbc.update("UPDATE chat_sessions SET title = ? WHERE session_id = ?", title, sessionId);
    }

    public void touchSession(String sessionId) {
        jdbc.update("UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?", sessionId);
    }

    public void deleteSession(String sessionId) {
        jdbc.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM chat_sessions WHERE session_id = ?", sessionId);
    }

    // ── Session title ─────────────────────────────────────────────

    /**
     * Auto-generate a short title from the first user message.
     */
    public String generateTitle(String firstMessage) {
        String text = firstMessage.trim();
        if (text.length() <= 30) return text;
        // Truncate at nearest sentence or word boundary
        int cut = text.lastIndexOf("。", Math.min(25, text.length()));
        if (cut < 5) cut = text.lastIndexOf(" ", Math.min(30, text.length()));
        if (cut < 5) cut = Math.min(28, text.length());
        return text.substring(0, cut) + (cut < text.length() ? "…" : "");
    }

    // ── Record ────────────────────────────────────────────────────

    public record Session(
        String sessionId,
        String title,
        Date createdAt,
        Date updatedAt
    ) {}
}
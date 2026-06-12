package com.agentloop.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Document registry backed by Milvus vector store.
 * Metadata is persisted in MySQL; vector data is stored in Milvus.
 */
@Service
@Slf4j
public class DocumentRegistry {

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    private final JdbcTemplate jdbc;

    /** DashScope embedding API accepts max 25 texts per call */
    private static final int EMBEDDING_BATCH_SIZE = 25;
    private static final String DEFAULT_KNOWLEDGE_BASE_ID = "default";

    public DocumentRegistry(VectorStore vectorStore, DocumentChunker chunker, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void load() {
        ensureKnowledgeBaseSchema();
        log.info("DocumentRegistry starting — vector store: {}, persisted chunks: {}",
                vectorStore.getClass().getSimpleName(), countDocuments());
    }

    @Transactional
    public synchronized void addDocument(String content, String source) {
        addDocument(content, source, DEFAULT_KNOWLEDGE_BASE_ID);
    }

    @Transactional
    public synchronized void addDocument(String content, String source, String knowledgeBaseId) {
        String kbId = normalizeKnowledgeBaseId(knowledgeBaseId);
        ensureKnowledgeBaseExists(kbId);
        log.info("addDocument called — vector store: {}", vectorStore.getClass().getSimpleName());
        if (existsContent(content, kbId)) {
            log.info("duplicate content, skipping");
            return;
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        log.info("chunked into {} chunks", chunks.size());
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of(
                        "source", source,
                        "chunk_id", chunk.id(),
                        "content", chunk.content(),
                        "knowledge_base_id", kbId)))
            .toList();

        // Write to vector store FIRST. If MySQL fails afterwards, compensate by
        // removing the vectors we just added — keeps Milvus and MySQL in sync.
        // (Cross-store atomicity between MySQL and Milvus isn't achievable with
        // a single local transaction; this is the best we can do without an
        // outbox/saga pattern.)
        log.info("calling vectorStore.add() in batches of {}...", EMBEDDING_BATCH_SIZE);
        try {
            addDocumentsBatched(docs);
        } catch (Exception e) {
            log.error("vectorStore.add() failed, nothing to roll back: {}", e.getMessage());
            throw e;
        }
        log.info("vectorStore.add() completed");

        try {
            saveChunks(chunks, kbId);
        } catch (Exception e) {
            log.error("MySQL save failed, compensating with vectorStore.delete(): {}", e.getMessage());
            try {
                List<String> ids = chunks.stream().map(DocumentChunker.DocumentChunk::id).toList();
                vectorStore.delete(ids);
            } catch (Exception ce) {
                log.error("Compensation delete failed: {}", ce.getMessage());
            }
            throw e;
        }
        log.info("persisted document chunks: {}", countDocuments());
    }

    /**
     * Add documents in batches — DashScope API limit is 25 texts per embedding call.
     */
    private void addDocumentsBatched(List<Document> docs) {
        for (int i = 0; i < docs.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, docs.size());
            List<Document> batch = docs.subList(i, end);
            log.info("  batch {} ({} docs)", (i / EMBEDDING_BATCH_SIZE + 1), batch.size());
            vectorStore.add(batch);
        }
    }

    public synchronized void deleteDocument(String id) {
        jdbc.update("DELETE FROM rag_documents WHERE id = ?", id);
        vectorStore.delete(List.of(id));
    }

    public synchronized void clear() {
        clear(DEFAULT_KNOWLEDGE_BASE_ID);
    }

    public synchronized void clear(String knowledgeBaseId) {
        String kbId = normalizeKnowledgeBaseId(knowledgeBaseId);
        // MySQL is the source of truth for IDs. Read them first, then
        // delete the corresponding vectors from the vector store, then wipe MySQL.
        // vectorStore.delete(List.of()) is a no-op on Milvus, so we must enumerate.
        List<String> allIds;
        try {
            allIds = jdbc.query(
                    "SELECT id FROM rag_documents WHERE knowledge_base_id = ?",
                    (rs, rowNum) -> rs.getString("id"),
                    kbId
            );
        } catch (Exception e) {
            log.error("Failed to read ids for clear: {}", e.getMessage());
            allIds = List.of();
        }

        if (!allIds.isEmpty()) {
            try {
                vectorStore.delete(allIds);
                log.info("Cleared {} vectors from vector store", allIds.size());
            } catch (Exception e) {
                log.error("Vector delete failed: {}", e.getMessage());
            }
        }

        try {
            int rows = jdbc.update("DELETE FROM rag_documents WHERE knowledge_base_id = ?", kbId);
            log.info("Cleared {} metadata rows from MySQL", rows);
        } catch (Exception e) {
            log.error("MySQL clear failed: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> listDocuments() {
        return listDocuments(DEFAULT_KNOWLEDGE_BASE_ID);
    }

    public List<Map<String, String>> listDocuments(String knowledgeBaseId) {
        String kbId = normalizeKnowledgeBaseId(knowledgeBaseId);
        return jdbc.query(
                "SELECT id, knowledge_base_id, content, source FROM rag_documents WHERE knowledge_base_id = ? ORDER BY created_at DESC, id DESC",
                (rs, rowNum) -> Map.of(
                        "id", rs.getString("id"),
                        "knowledgeBaseId", rs.getString("knowledge_base_id"),
                        "content", rs.getString("content"),
                        "source", rs.getString("source")
                ),
                kbId
        );
    }

    public List<Map<String, String>> listKnowledgeBases() {
        return jdbc.query(
                "SELECT id, name, description FROM knowledge_bases ORDER BY created_at ASC, name ASC",
                (rs, rowNum) -> Map.of(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "description", rs.getString("description")
                )
        );
    }

    public synchronized Map<String, String> createKnowledgeBase(String name, String description) {
        String normalizedName = name == null || name.isBlank() ? "未命名知识库" : name.trim();
        String normalizedDescription = description == null ? "" : description.trim();
        String id = "kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, name, description) VALUES (?, ?, ?)",
                id,
                normalizedName,
                normalizedDescription
        );
        return Map.of("id", id, "name", normalizedName, "description", normalizedDescription);
    }

    private boolean existsContent(String content, String knowledgeBaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE knowledge_base_id = ? AND content = ?",
                Integer.class,
                normalizeKnowledgeBaseId(knowledgeBaseId),
                content
        );
        return count != null && count > 0;
    }

    private void saveChunks(List<DocumentChunker.DocumentChunk> chunks, String knowledgeBaseId) {
        String kbId = normalizeKnowledgeBaseId(knowledgeBaseId);
        for (var chunk : chunks) {
            jdbc.update(
                    "INSERT INTO rag_documents (id, knowledge_base_id, content, source) VALUES (?, ?, ?, ?)",
                    chunk.id(),
                    kbId,
                    chunk.content(),
                    chunk.source()
            );
        }
    }

    private int countDocuments() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rag_documents", Integer.class);
        return count != null ? count : 0;
    }

    private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
        return knowledgeBaseId == null || knowledgeBaseId.isBlank() ? DEFAULT_KNOWLEDGE_BASE_ID : knowledgeBaseId.trim();
    }

    private void ensureKnowledgeBaseExists(String knowledgeBaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE id = ?",
                Integer.class,
                normalizeKnowledgeBaseId(knowledgeBaseId)
        );
        if (count == null || count == 0) {
            throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseId);
        }
    }

    private void ensureKnowledgeBaseSchema() {
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    description VARCHAR(512) NOT NULL DEFAULT '',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbc.update(
                "INSERT IGNORE INTO knowledge_bases (id, name, description) VALUES (?, ?, ?)",
                DEFAULT_KNOWLEDGE_BASE_ID,
                "默认知识库",
                "系统默认知识库"
        );

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'rag_documents'
                  AND column_name = 'knowledge_base_id'
                """, Integer.class);
        if (count == null || count == 0) {
            jdbc.update("ALTER TABLE rag_documents ADD COLUMN knowledge_base_id VARCHAR(64) NOT NULL DEFAULT 'default' AFTER id");
            jdbc.update("CREATE INDEX idx_knowledge_base_id ON rag_documents (knowledge_base_id)");
        }
    }
}

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

    public DocumentRegistry(VectorStore vectorStore, DocumentChunker chunker, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void load() {
        log.info("DocumentRegistry starting — vector store: {}, persisted chunks: {}",
                vectorStore.getClass().getSimpleName(), countDocuments());
    }

    @Transactional
    public synchronized void addDocument(String content, String source) {
        log.info("addDocument called — vector store: {}", vectorStore.getClass().getSimpleName());
        if (existsContent(content)) {
            log.info("duplicate content, skipping");
            return;
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        log.info("chunked into {} chunks", chunks.size());
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
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
            saveChunks(chunks);
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
        // MySQL is the source of truth for IDs. Read them first, then
        // delete the corresponding vectors from the vector store, then wipe MySQL.
        // vectorStore.delete(List.of()) is a no-op on Milvus, so we must enumerate.
        List<String> allIds;
        try {
            allIds = jdbc.query(
                    "SELECT id FROM rag_documents",
                    (rs, rowNum) -> rs.getString("id")
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
            int rows = jdbc.update("DELETE FROM rag_documents");
            log.info("Cleared {} metadata rows from MySQL", rows);
        } catch (Exception e) {
            log.error("MySQL clear failed: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> listDocuments() {
        return jdbc.query(
                "SELECT id, content, source FROM rag_documents ORDER BY created_at DESC, id DESC",
                (rs, rowNum) -> Map.of(
                        "id", rs.getString("id"),
                        "content", rs.getString("content"),
                        "source", rs.getString("source")
                )
        );
    }

    private boolean existsContent(String content) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE content = ?",
                Integer.class,
                content
        );
        return count != null && count > 0;
    }

    private void saveChunks(List<DocumentChunker.DocumentChunk> chunks) {
        for (var chunk : chunks) {
            jdbc.update(
                    "INSERT INTO rag_documents (id, content, source) VALUES (?, ?, ?)",
                    chunk.id(),
                    chunk.content(),
                    chunk.source()
            );
        }
    }

    private int countDocuments() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rag_documents", Integer.class);
        return count != null ? count : 0;
    }
}

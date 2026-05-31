package com.agentloop.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

/**
 * Document registry backed by Milvus vector store.
 * Metadata is persisted in MySQL; vector data is stored in Milvus.
 */
@Service
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
        System.out.println("[DocumentRegistry] Starting, MySQL stores document metadata and Milvus stores vectors.");
        System.out.println("[DocumentRegistry] VectorStore bean: " + vectorStore.getClass().getName());
        System.out.println("[DocumentRegistry] persisted document chunks: " + countDocuments());

        // Diagnose which Milvus we're actually connected to
        try {
            String host = vectorStore.getClass().getDeclaredField("host").get(vectorStore).toString();
            String port = vectorStore.getClass().getDeclaredField("port").get(vectorStore).toString();
            System.out.println("[DocumentRegistry] Milvus connection: " + host + ":" + port);
        } catch (Exception e) {
            System.out.println("[DocumentRegistry] Cannot read host/port from VectorStore: " + e.getMessage());
        }
    }

    private synchronized void reindex(String content, String source) {
        if (existsContent(content)) return;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
            .toList();

        System.out.println("[DocumentRegistry] reindex: adding " + docs.size() + " chunks to vectorStore");
        addDocumentsBatched(docs);
        saveChunks(chunks);
    }

    public synchronized void addDocument(String content, String source) {
        System.out.println("[DocumentRegistry] addDocument called — vectorStore class: " + vectorStore.getClass().getName());
        if (existsContent(content)) {
            System.out.println("[DocumentRegistry] duplicate content, skipping");
            return;
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        System.out.println("[DocumentRegistry] chunked into " + chunks.size() + " chunks");
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
            .toList();

        System.out.println("[DocumentRegistry] calling vectorStore.add() in batches of " + EMBEDDING_BATCH_SIZE + "...");
        addDocumentsBatched(docs);
        System.out.println("[DocumentRegistry] vectorStore.add() completed");
        saveChunks(chunks);
        System.out.println("[DocumentRegistry] persisted document chunks: " + countDocuments());
    }

    /**
     * Add documents in batches — DashScope API limit is 25 texts per embedding call.
     */
    private void addDocumentsBatched(List<Document> docs) {
        for (int i = 0; i < docs.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, docs.size());
            List<Document> batch = docs.subList(i, end);
            System.out.println("[DocumentRegistry]   batch " + (i / EMBEDDING_BATCH_SIZE + 1) + " (" + batch.size() + " docs)");
            vectorStore.add(batch);
        }
    }

    public synchronized void deleteDocument(String id) {
        jdbc.update("DELETE FROM rag_documents WHERE id = ?", id);
        vectorStore.delete(List.of(id));
    }

    public synchronized void clear() {
        try {
            vectorStore.delete(List.of());
        } catch (Exception e) {
            System.err.println("[DocumentRegistry] Clear failed: " + e.getMessage());
        }
        jdbc.update("DELETE FROM rag_documents");
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

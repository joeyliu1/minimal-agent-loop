package com.agentloop.rag;

import io.milvus.client.MilvusClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Document registry backed by Milvus vector store.
 * Metadata kept in memory; vector data in Milvus.
 * No JSON file persistence — Milvus is the source of truth.
 */
@Service
public class DocumentRegistry {

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    private final EmbeddingModel embeddingModel;

    /** DashScope embedding API accepts max 25 texts per call */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    private record DocumentRecord(String id, String content, String source) {}

    private final List<DocumentRecord> documentRegistry = new ArrayList<>();

    public DocumentRegistry(VectorStore vectorStore, DocumentChunker chunker, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void load() {
        System.out.println("[DocumentRegistry] Starting, Milvus is source of truth — no JSON backup.");
        System.out.println("[DocumentRegistry] VectorStore bean: " + vectorStore.getClass().getName());

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
        if (documentRegistry.stream().anyMatch(r -> r.content().equals(content))) return;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
            .toList();

        System.out.println("[DocumentRegistry] reindex: adding " + docs.size() + " chunks to vectorStore");
        addDocumentsBatched(docs);
        for (var chunk : chunks) {
            documentRegistry.add(new DocumentRecord(chunk.id(), chunk.content(), source));
        }
    }

    public synchronized void addDocument(String content, String source) {
        System.out.println("[DocumentRegistry] addDocument called — vectorStore class: " + vectorStore.getClass().getName());
        if (documentRegistry.stream().anyMatch(r -> r.content().equals(content))) {
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
        for (var chunk : chunks) {
            documentRegistry.add(new DocumentRecord(chunk.id(), chunk.content(), source));
        }
        System.out.println("[DocumentRegistry] documentRegistry now has " + documentRegistry.size() + " entries");
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
        documentRegistry.removeIf(r -> r.id().equals(id));
        vectorStore.delete(List.of(id));
    }

    public synchronized void clear() {
        try {
            vectorStore.delete(List.of());
        } catch (Exception e) {
            System.err.println("[DocumentRegistry] Clear failed: " + e.getMessage());
        }
        documentRegistry.clear();
    }

    public List<Map<String, String>> listDocuments() {
        return documentRegistry.stream()
            .map(r -> Map.of("id", r.id(), "content", r.content(), "source", r.source()))
            .toList();
    }
}
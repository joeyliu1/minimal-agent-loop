package com.agentloop.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indexing pipeline: chunk → embed → store in vector store.
 * Handles document ingestion for RAG.
 */
@Service
public class IndexingService {

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    // In-memory document registry for listing/clearing
    private final List<DocumentRecord> documentRegistry = new ArrayList<>();

    private record DocumentRecord(String id, String content, String source) {}

    public IndexingService(VectorStore vectorStore, DocumentChunker chunker) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
    }

    /**
     * Index a document from raw text.
     */
    public void indexDocument(String content, String source) {
        indexDocument(content, source, Map.of());
    }

    /**
     * Index a document with metadata.
     */
    public void indexDocument(String content, String source, Map<String, Object> metadata) {
        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);

        List<Document> documents = chunks.stream()
                .map(chunk -> {
                    Document doc = new Document(
                            chunk.id(),
                            chunk.content(),
                            Map.of(
                                    "source", source,
                                    "chunk_id", chunk.id(),
                                    "content", chunk.content()
                            )
                    );
                    return doc;
                })
                .collect(java.util.stream.Collectors.toList());

        vectorStore.add(documents);
        // Track in registry
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            documentRegistry.add(new DocumentRecord(chunk.id(), chunk.content(), source));
        }
    }

    /**
     * Index multiple documents.
     */
    public void indexDocuments(List<String> contents, List<String> sources) {
        if (contents.size() != sources.size()) {
            throw new IllegalArgumentException("contents and sources must have same size");
        }
        for (int i = 0; i < contents.size(); i++) {
            indexDocument(contents.get(i), sources.get(i));
        }
    }

    public void addDocument(String content, String source) {
        indexDocument(content, source);
    }

    public void deleteDocument(String id) {
        documentRegistry.removeIf(r -> r.id().equals(id));
        vectorStore.delete(List.of(id));
    }

    public void clear() {
        // Delete all tracked documents from vector store
        List<String> allIds = documentRegistry.stream().map(DocumentRecord::id).toList();
        if (!allIds.isEmpty()) {
            vectorStore.delete(allIds);
        }
        documentRegistry.clear();
    }

    public List<Map<String, String>> listDocuments() {
        return documentRegistry.stream()
                .map(r -> Map.of("id", r.id(), "content", r.content(), "source", r.source()))
                .toList();
    }
}
package com.agentloop.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indexing pipeline: chunk → embed → store in vector store.
 * Handles document ingestion for RAG.
 */
@Service
public class IndexingService {

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;

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
}
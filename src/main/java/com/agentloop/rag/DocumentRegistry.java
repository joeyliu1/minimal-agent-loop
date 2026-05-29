package com.agentloop.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists document registry to JSON file for durability.
 * Vector embeddings are re-generated on startup from stored content.
 */
@Service
public class DocumentRegistry {

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final Path registryPath;

    private record DocumentRecord(String id, String content, String source) {}

    private final List<DocumentRecord> documentRegistry = new ArrayList<>();

    public DocumentRegistry(VectorStore vectorStore, DocumentChunker chunker, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        // Store in project root
        this.registryPath = Paths.get(System.getProperty("user.dir"), "knowledge_registry.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(registryPath)) return;

        try {
            String json = Files.readString(registryPath);
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode array = mapper.readTree(json).isArray()
                ? (ArrayNode) mapper.readTree(json)
                : mapper.createArrayNode();

            int loaded = 0;
            for (var node : array) {
                String content = node.has("content") ? node.get("content").asText() : "";
                String source = node.has("source") ? node.get("source").asText() : "";
                if (!content.isBlank()) {
                    reindex(content, source);
                    loaded++;
                }
            }
            System.out.println("[DocumentRegistry] Loaded " + loaded + " documents from registry");
        } catch (Exception e) {
            System.err.println("[DocumentRegistry] Failed to load registry: " + e.getMessage());
        }
    }

    private synchronized void reindex(String content, String source) {
        // Avoid duplicates by checking content hash
        if (documentRegistry.stream().anyMatch(r -> r.content().equals(content))) return;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
            .toList();

        vectorStore.add(docs);
        for (var chunk : chunks) {
            documentRegistry.add(new DocumentRecord(chunk.id(), chunk.content(), source));
        }
    }

    public synchronized void addDocument(String content, String source) {
        if (documentRegistry.stream().anyMatch(r -> r.content().equals(content))) return;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(content, source);
        List<Document> docs = chunks.stream()
            .map(chunk -> new Document(chunk.id(), chunk.content(),
                Map.of("source", source, "chunk_id", chunk.id(), "content", chunk.content())))
            .toList();

        vectorStore.add(docs);
        for (var chunk : chunks) {
            documentRegistry.add(new DocumentRecord(chunk.id(), chunk.content(), source));
        }
        save();
    }

    public synchronized void deleteDocument(String id) {
        documentRegistry.removeIf(r -> r.id().equals(id));
        vectorStore.delete(List.of(id));
        save();
    }

    public synchronized void clear() {
        documentRegistry.forEach(r -> vectorStore.delete(List.of(r.id())));
        documentRegistry.clear();
        save();
    }

    public List<Map<String, String>> listDocuments() {
        return documentRegistry.stream()
            .map(r -> Map.of("id", r.id(), "content", r.content(), "source", r.source()))
            .toList();
    }

    private void save() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            ArrayNode array = mapper.createArrayNode();

            for (var record : documentRegistry) {
                ObjectNode node = mapper.createObjectNode();
                node.put("id", record.id());
                node.put("content", record.content());
                node.put("source", record.source());
                array.add(node);
            }

            Files.writeString(registryPath, mapper.writeValueAsString(array));
        } catch (Exception e) {
            System.err.println("[DocumentRegistry] Failed to save registry: " + e.getMessage());
        }
    }
}
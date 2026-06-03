package com.agentloop.rag;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Indexing pipeline: chunk → embed → store in vector store.
 * Delegates to DocumentRegistry for persistence.
 */
@Service
public class IndexingService {

    private final DocumentRegistry registry;

    public IndexingService(DocumentRegistry registry) {
        this.registry = registry;
    }

    public void addDocument(String content, String source) {
        registry.addDocument(content, source);
    }

    public void addDocument(String content, String source, String knowledgeBaseId) {
        registry.addDocument(content, source, knowledgeBaseId);
    }

    public void deleteDocument(String id) {
        registry.deleteDocument(id);
    }

    public void clear() {
        registry.clear();
    }

    public void clear(String knowledgeBaseId) {
        registry.clear(knowledgeBaseId);
    }

    public List<Map<String, String>> listDocuments() {
        return registry.listDocuments();
    }

    public List<Map<String, String>> listDocuments(String knowledgeBaseId) {
        return registry.listDocuments(knowledgeBaseId);
    }

    public List<Map<String, String>> listKnowledgeBases() {
        return registry.listKnowledgeBases();
    }

    public Map<String, String> createKnowledgeBase(String name, String description) {
        return registry.createKnowledgeBase(name, description);
    }
}

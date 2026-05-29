package com.agentloop.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding service using DashScope text-embedding-v3.
 * Converts text chunks into vector embeddings for RAG retrieval.
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Generate embedding vector for a single text.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        return embeddingModel.embed(text);
    }

    /**
     * Generate embedding vectors for multiple texts.
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return embeddingModel.embed(texts);
    }
}
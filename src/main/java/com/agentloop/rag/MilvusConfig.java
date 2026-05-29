package com.agentloop.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vector Store configuration for RAG.
 * Uses in-memory SimpleVectorStore for development.
 * Can be configured to use Milvus or other vector stores in production.
 */
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection:agent_docs}")
    private String collection;

    @Value("${milvus.dimension:1536}")
    private int dimension;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        // For development: use in-memory SimpleVectorStore
        // For production: replace with MilvusVectorStore.builder(...)
        return org.springframework.ai.vectorstore.SimpleVectorStore.builder(embeddingModel)
                .build();
    }
}
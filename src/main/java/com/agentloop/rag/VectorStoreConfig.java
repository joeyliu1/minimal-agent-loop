package com.agentloop.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vector Store configuration for RAG.
 *
 * Current: In-memory SimpleVectorStore (no external dependencies)
 *
 * To switch to Milvus:
 * 1. Start Milvus server: docker run -d -p 19530:19530 milvusdb/milvus:latest
 * 2. Add Milvus SDK dependency to pom.xml (see commented code below)
 * 3. Uncomment @Configuration and Milvus bean method
 * 4. Set environment variables: MILVUS_HOST, MILVUS_PORT, MILVUS_COLLECTION
 * 5. Comment out SimpleVectorStore method
 */
@Configuration
public class VectorStoreConfig {

    // ========== Milvus Configuration (commented out by default) ==========
    // @Value("${milvus.host:localhost}")
    // private String host;
    //
    // @Value("${milvus.port:19530}")
    // private int port;
    //
    // @Value("${milvus.collection:agent_docs}")
    // private String collection;
    //
    // @Value("${milvus.dimension:1536}")
    // private int dimension;
    //
    // @Value("${milvus.username:}")
    // private String username;
    //
    // @Value("${milvus.password:}")
    // private String password;

    /**
     * Current: In-memory SimpleVectorStore
     * Works out of the box with no external dependencies.
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel)
                .build();
    }

    /**
     * Milvus VectorStore (commented out)
     * Uncomment and configure when Milvus is available.
     *
     * Maven dependency needed (add to pom.xml):
     * <dependency>
     *     <groupId>io.milvus</groupId>
     *     <artifactId>milvus-sdk-java</artifactId>
     *     <version>2.4.0</version>
     * </dependency>
     *
     * Code to use:
     * return MilvusVectorStore.builder(embeddingModel)
     *         .host(host).port(port)
     *         .collectionName(collection)
     *         .build();
     */
    // @Bean
    // public VectorStore milvusVectorStore(EmbeddingModel embeddingModel) {
    //     return MilvusVectorStore.builder(embeddingModel)
    //             .host(host).port(port)
    //             .collectionName(collection)
    //             .build();
    // }
}
package com.agentloop.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual Milvus VectorStore configuration.
 * WORKAROUND for Spring AI bug: MilvusVectorStoreAutoConfiguration ignores
 * collection-name, database-name, embedding-dimension and other properties.
 * Only initializeSchema was passed — all other config was silently dropped.
 * See: https://github.com/spring-projects/spring-ai/issues/2297
 */
@Configuration
public class MilvusVectorStoreConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;

    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    @Value("${spring.ai.vectorstore.milvus.collection-name:agent_knowledge}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1536}")
    private int embeddingDimension;

    @Bean
    @ConditionalOnMissingBean(MilvusServiceClient.class)
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    @Bean
    @ConditionalOnMissingBean(MilvusVectorStore.class)
    public MilvusVectorStore vectorStore(MilvusServiceClient milvusServiceClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .initializeSchema(true)
                .build();
    }
}
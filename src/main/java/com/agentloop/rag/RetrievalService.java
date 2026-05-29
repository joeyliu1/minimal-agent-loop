package com.agentloop.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG retrieval: search relevant chunks from vector store and generate answer with citations.
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RetrievalService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                    You are a helpful RAG assistant. Use the provided context to answer the user's question.
                    If the context doesn't contain relevant information, say you don't know.
                    Always cite your sources using [source] markers.
                    """)
                .build();
    }

    /**
     * Retrieve relevant documents for a query.
     */
    public List<RetrievedDocument> retrieve(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .map(doc -> new RetrievedDocument(
                        doc.getId(),
                        doc.getText(),
                        doc.getMetadata().getOrDefault("source", "unknown").toString(),
                        doc.getMetadata().getOrDefault("score", "0.0").toString()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Generate answer with citations from retrieved documents.
     */
    public String answerWithCitations(String query, List<RetrievedDocument> docs) {
        if (docs.isEmpty()) {
            return "No relevant documents found. Please try a different query.";
        }

        // Build context with clear source markers
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            RetrievedDocument d = docs.get(i);
            contextBuilder.append(String.format("【文档%d - 来源: %s】\n%s\n\n",
                    i + 1, d.source(), d.content()));
        }
        String context = contextBuilder.toString().trim();

        PromptTemplate template = new PromptTemplate("""
                你是一个严格的问答助手。

                ## 知识库（这是你唯一的参考来源）
                {context}

                ## 用户问题
                {question}

                ## 严格规则
                1. 只使用知识库中存在的文字来回答，不要添加任何知识库中没有的信息
                2. 如果知识库中没有能回答问题的内容，回复："抱歉，知识库中没有相关信息"
                3. 回答中的事实必须来自知识库，禁止编造或补充
                4. 在回答末尾必须标注来源，格式：[来源: xxx]
                5. 如果使用了多条文档，标注所有来源：[来源: xxx] [来源: yyy]
                """);

        Prompt prompt = template.create(Map.of("context", context, "question", query));

        String answer = chatClient.prompt(prompt)
                .call()
                .content();

        return answer != null ? answer : "[error] Failed to generate answer";
    }

    /**
     * Full RAG flow: retrieve + answer with citations.
     */
    public String ragAnswer(String query, int topK) {
        List<RetrievedDocument> docs = retrieve(query, topK);
        return answerWithCitations(query, docs);
    }

    /**
     * Retrieved document metadata.
     */
    public record RetrievedDocument(String id, String content, String source, String score) {}
}
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

        String context = docs.stream()
                .map(d -> String.format("[%s] %s", d.source(), d.content()))
                .collect(Collectors.joining("\n\n"));

        PromptTemplate template = new PromptTemplate("""
                Context:
                {context}

                Question: {question}

                Answer the question based on the context above. Cite your sources like this: [source]
                """);

        Prompt prompt = template.create(Map.of("context", context, "question", query));

        String answer = chatClient.prompt(prompt)
                .user(query)
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
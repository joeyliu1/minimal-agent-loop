package com.agentloop.tools;

import com.agentloop.rag.IndexingService;
import com.agentloop.rag.RetrievalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG tools: query knowledge base and add documents.
 */
@Component
public class RagTool {

    private final RetrievalService retrievalService;
    private final IndexingService indexingService;

    public RagTool(RetrievalService retrievalService, IndexingService indexingService) {
        this.retrievalService = retrievalService;
        this.indexingService = indexingService;
    }

    @Tool(name = "rag_query", description = "Query the knowledge base for relevant information. Use this when the user asks about topics that might be in the knowledge base.")
    public String queryKnowledgeBase(@ToolParam(description = "The search query to find relevant information") String query) {
        return retrievalService.ragAnswer(query, 3);
    }

    @Tool(name = "rag_add_document", description = "Add a document to the knowledge base for future retrieval.")
    public String addDocument(
            @ToolParam(description = "The document content to add") String content,
            @ToolParam(description = "The source or title of the document") String source
    ) {
        indexingService.addDocument(content, source);
        return "Document added successfully: " + source;
    }

    @Tool(name = "rag_add_documents", description = "Add multiple documents to the knowledge base.")
    public String addDocuments(
            @ToolParam(description = "List of document contents") List<String> contents,
            @ToolParam(description = "List of sources/titles") List<String> sources
    ) {
        for (int i = 0; i < contents.size(); i++) {
            indexingService.addDocument(contents.get(i), sources.get(i));
        }
        return "Added " + contents.size() + " documents successfully";
    }
}
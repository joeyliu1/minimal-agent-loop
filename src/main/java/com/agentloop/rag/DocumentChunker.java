package com.agentloop.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Document chunking: split large documents into smaller pieces.
 * Simple implementation using character-based splitting with sentence boundary awareness.
 */
@Component
public class DocumentChunker {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    /**
     * Chunk a document by its content using simple character-based splitting.
     */
    public List<DocumentChunk> chunk(String content, String source) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return chunk(content, source, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /**
     * Chunk a document with custom size.
     */
    public List<DocumentChunk> chunk(String content, String source, int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int length = content.length();

        if (length <= chunkSize) {
            chunks.add(new DocumentChunk(UUID.randomUUID().toString(), content.trim(), source));
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;

        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // Try to break at sentence boundary
            if (end < length) {
                int lastPeriod = content.lastIndexOf('.', end);
                int lastNewline = content.lastIndexOf('\n', end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start + chunkSize / 2) {
                    end = breakPoint + 1;
                }
            }

            String chunkText = content.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new DocumentChunk(
                        UUID.randomUUID().toString() + "_" + chunkIndex,
                        chunkText,
                        source
                ));
            }

            start = end - overlap;
            if (start < 0) {
                start = end;
            }
            chunkIndex++;

            // Prevent infinite loops
            if (chunkIndex > 1000) {
                break;
            }
        }

        return chunks;
    }

    /**
     * Simple record for chunk metadata.
     */
    public record DocumentChunk(String id, String content, String source) {}
}
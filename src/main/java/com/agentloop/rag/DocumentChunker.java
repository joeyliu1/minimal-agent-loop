package com.agentloop.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recursive semantic chunking — splits at meaningful boundaries from large to small:
 * paragraphs → single newlines → sentence-ending punctuation → fixed-size character blocks.
 */
@Component
public class DocumentChunker {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    public List<DocumentChunk> chunk(String content, String source) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return chunk(content, source, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public List<DocumentChunk> chunk(String content, String source, int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        if (content.length() <= chunkSize) {
            chunks.add(new DocumentChunk(
                    UUID.randomUUID().toString().substring(0, 28) + "_000",
                    content.trim(), source));
            return chunks;
        }

        for (String piece : splitRecursive(content, chunkSize)) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                chunks.add(new DocumentChunk(
                        UUID.randomUUID().toString().substring(0, 30)
                                + String.format("_%03d", chunks.size()),
                        trimmed, source));
            }
        }

        return chunks;
    }

    private List<String> splitRecursive(String text, int maxSize) {
        List<String> result = new ArrayList<>();

        if (text.length() <= maxSize) {
            result.add(text);
            return result;
        }

        // Try splitting by paragraphs (double newlines)
        String[] paragraphs = text.split("\n\n+");
        if (paragraphs.length > 1) {
            List<String> merged = mergeItems(paragraphs, maxSize, "\n\n");
            for (String item : merged) {
                if (item.length() <= maxSize) {
                    result.add(item);
                } else {
                    result.addAll(splitRecursive(item, maxSize));
                }
            }
            return result;
        }

        // Try splitting by single newlines
        if (text.contains("\n")) {
            String[] lines = text.split("\n", -1);
            List<String> merged = mergeItems(lines, maxSize, "\n");
            for (String item : merged) {
                if (item.length() <= maxSize) {
                    result.add(item);
                } else {
                    result.addAll(splitRecursive(item, maxSize));
                }
            }
            return result;
        }

        // Try splitting by sentence-ending punctuation
        if (hasSentenceBreaks(text)) {
            List<String> sentences = splitBySentences(text);
            if (sentences.size() > 1) {
                List<String> merged = mergeItems(sentences.toArray(new String[0]), maxSize, " ");
                for (String item : merged) {
                    if (item.length() <= maxSize) {
                        result.add(item);
                    } else {
                        result.addAll(splitRecursive(item, maxSize));
                    }
                }
                return result;
            }
        }

        // Last resort: fixed-size character blocks with 50% overlap
        int step = maxSize / 2;
        for (int i = 0; i < text.length(); i += step) {
            String piece = text.substring(i, Math.min(i + maxSize, text.length())).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }
        }

        return result;
    }

    private List<String> mergeItems(String[] items, int maxSize, String separator) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLen = 0;

        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;

            int itemLen = trimmed.length();
            if (itemLen <= maxSize) {
                if (currentLen + itemLen + (current.length() > 0 ? separator.length() : 0) > maxSize) {
                    if (current.length() > 0) {
                        merged.add(current.toString());
                        current.setLength(0);
                        currentLen = 0;
                    }
                    if (itemLen < maxSize) {
                        merged.add(trimmed);
                    } else {
                        merged.addAll(splitRecursive(trimmed, maxSize));
                    }
                } else {
                    if (current.length() > 0) {
                        current.append(separator);
                        currentLen += separator.length();
                    }
                    current.append(trimmed);
                    currentLen += itemLen;
                }
            } else {
                if (current.length() > 0) {
                    merged.add(current.toString());
                    current.setLength(0);
                    currentLen = 0;
                }
                merged.addAll(splitRecursive(trimmed, maxSize));
            }
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }

    private boolean hasSentenceBreaks(String text) {
        return text.matches(".*[。！？.!?].*");
    }

    private List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        String[] parts = text.split("(?<=[。！？.!?])\\s*");
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty()) {
                sentences.add(t);
            }
        }
        return sentences;
    }

    public record DocumentChunk(String id, String content, String source) {}
}
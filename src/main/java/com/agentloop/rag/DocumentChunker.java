package com.agentloop.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Markdown-aware recursive chunking.
 * Splits at meaningful boundaries from large to small:
 * markdown headings → paragraphs → single newlines → sentence-ending punctuation → fixed-size character blocks.
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

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<DocumentChunk> chunks = new ArrayList<>();
        List<String> pieces = splitMarkdownAware(normalized, chunkSize, overlap);
        for (String piece : pieces) {
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

    private List<String> splitMarkdownAware(String text, int maxSize, int overlap) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }

        List<String> sections = splitByMarkdownHeadings(text);
        if (sections.size() <= 1) {
            return splitRecursive(text, maxSize, overlap);
        }

        List<String> result = new ArrayList<>();
        String current = "";
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.length() > maxSize) {
                if (!current.isBlank()) {
                    result.add(current);
                    current = "";
                }
                result.addAll(splitRecursive(trimmed, maxSize, overlap));
                continue;
            }

            if (current.isBlank()) {
                current = trimmed;
            } else if (current.length() + 2 + trimmed.length() <= maxSize) {
                current = current + "\n\n" + trimmed;
            } else {
                result.add(current);
                current = trimmed;
            }
        }

        if (!current.isBlank()) {
            result.add(current);
        }
        return addOverlap(result, maxSize, overlap);
    }

    private List<String> splitByMarkdownHeadings(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = text.split("\n", -1);

        for (String line : lines) {
            if (isSectionHeading(line) && current.toString().contains("\n## ")) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    private boolean isSectionHeading(String line) {
        return line.matches("^##\\s+.+");
    }

    private List<String> splitRecursive(String text, int maxSize, int overlap) {
        List<String> result = new ArrayList<>();

        if (text.length() <= maxSize) {
            result.add(text);
            return result;
        }

        // Try splitting by paragraphs (double newlines)
        String[] paragraphs = text.split("\n\n+");
        if (paragraphs.length > 1) {
            List<String> merged = mergeItems(paragraphs, maxSize, "\n\n", overlap);
            for (String item : merged) {
                if (item.length() <= maxSize) {
                    result.add(item);
                } else {
                    result.addAll(splitRecursive(item, maxSize, overlap));
                }
            }
            return addOverlap(result, maxSize, overlap);
        }

        // Try splitting by single newlines
        if (text.contains("\n")) {
            String[] lines = text.split("\n", -1);
            List<String> merged = mergeItems(lines, maxSize, "\n", overlap);
            for (String item : merged) {
                if (item.length() <= maxSize) {
                    result.add(item);
                } else {
                    result.addAll(splitRecursive(item, maxSize, overlap));
                }
            }
            return addOverlap(result, maxSize, overlap);
        }

        // Try splitting by sentence-ending punctuation
        if (hasSentenceBreaks(text)) {
            List<String> sentences = splitBySentences(text);
            if (sentences.size() > 1) {
                List<String> merged = mergeItems(sentences.toArray(new String[0]), maxSize, " ", overlap);
                for (String item : merged) {
                    if (item.length() <= maxSize) {
                        result.add(item);
                    } else {
                        result.addAll(splitRecursive(item, maxSize, overlap));
                    }
                }
                return addOverlap(result, maxSize, overlap);
            }
        }

        // Last resort: fixed-size character blocks with configured overlap
        int step = Math.max(1, maxSize - Math.max(0, overlap));
        for (int i = 0; i < text.length(); i += step) {
            String piece = text.substring(i, Math.min(i + maxSize, text.length())).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }
        }

        return result;
    }

    private List<String> mergeItems(String[] items, int maxSize, String separator, int overlap) {
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
                        merged.addAll(splitRecursive(trimmed, maxSize, overlap));
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
                merged.addAll(splitRecursive(trimmed, maxSize, overlap));
            }
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }

    private List<String> addOverlap(List<String> chunks, int maxSize, int overlap) {
        if (chunks.size() <= 1 || overlap <= 0) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).trim();
            String current = chunks.get(i).trim();
            String prefix = tail(previous, Math.min(overlap, previous.length())).trim();
            String joined = prefix.isBlank() ? current : prefix + "\n\n" + current;
            result.add(joined.length() <= maxSize ? joined : current);
        }
        return result;
    }

    private String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(text.length() - maxChars);
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

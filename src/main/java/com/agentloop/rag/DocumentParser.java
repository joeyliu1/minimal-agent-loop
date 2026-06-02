package com.agentloop.rag;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parse various file formats into text content.
 */
@Service
public class DocumentParser {

    public String parse(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "";

        String ext = filename.contains(".")
            ? filename.substring(filename.lastIndexOf(".") + 1).toLowerCase()
            : "";

        return switch (ext) {
            case "txt", "md", "csv", "log" -> parseText(file);
            case "json" -> parseJson(file);
            case "xml", "html", "htm" -> parseXml(file);
            case "java", "py", "js", "ts", "go", "sql", "c", "cpp", "h", "hpp", "cs", "rb", "php", "swift", "kt", "scala" -> parseText(file);
            case "yml", "yaml" -> parseYaml(file);
            case "properties" -> parseProperties(file);
            case "pdf" -> parsePdf(file);
            case "docx" -> parseDocx(file);
            case "zip" -> parseZip(file);
            default -> parseText(file);
        };
    }

    private String parseText(MultipartFile file) throws Exception {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private String parseJson(MultipartFile file) throws Exception {
        String json = new String(file.getBytes(), StandardCharsets.UTF_8);
        return extractJsonText(json);
    }

    /**
     * Walk a JSON tree and collect string values plus numeric/boolean nodes
     * into a plain-text representation suitable for chunking/embedding.
     */
    private String extractJsonText(String json) {
        try {
            JsonNode root = JSON_MAPPER.readTree(json);
            StringBuilder sb = new StringBuilder();
            walk(root, sb);
            return sb.toString().trim();
        } catch (Exception e) {
            // Malformed JSON: fall back to raw text so the user still gets something
            return json;
        }
    }

    private void walk(JsonNode node, StringBuilder sb) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> walk(e.getValue(), sb));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, sb));
        } else if (node.isTextual()) {
            String s = node.asText();
            if (!s.isBlank()) sb.append(s).append('\n');
        } else if (node.isNumber() || node.isBoolean()) {
            sb.append(node.asText()).append('\n');
        }
    }

    private String parseXml(MultipartFile file) throws Exception {
        String xml = new String(file.getBytes(), StandardCharsets.UTF_8);
        return stripTags(xml);
    }

    private String stripTags(String xml) {
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        boolean inContent = false;
        StringBuilder textContent = new StringBuilder();

        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '<') {
                inTag = true;
                if (inContent && textContent.length() > 0) {
                    String trimmed = textContent.toString().trim();
                    if (!trimmed.isEmpty()) sb.append(trimmed).append("\n");
                    textContent = new StringBuilder();
                }
                inContent = false;
                continue;
            }
            if (c == '>') {
                inTag = false;
                continue;
            }
            if (!inTag) {
                if (!Character.isWhitespace(c)) {
                    inContent = true;
                }
                if (inContent) {
                    textContent.append(c);
                }
            }
        }
        if (textContent.length() > 0) {
            String trimmed = textContent.toString().trim();
            if (!trimmed.isEmpty()) sb.append(trimmed).append("\n");
        }
        return sb.toString().trim();
    }

    private String parseYaml(MultipartFile file) throws Exception {
        return parseText(file);
    }

    private String parseProperties(MultipartFile file) throws Exception {
        String content = parseText(file);
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.startsWith("#") && line.contains("=")) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String parsePdf(MultipartFile file) throws Exception {
        // Minimal PDF text extraction. Scans for BT/ET (Begin/End text) operators
        // and collects ASCII bytes between them. Only works for uncompressed,
        // ASCII-only PDFs (CJK or compressed-stream PDFs will yield empty/garbled
        // output). For full PDF support, add Apache PDFBox to pom.xml and use
        // PDFTextStripper here.
        byte[] bytes = file.getBytes();
        StringBuilder sb = new StringBuilder();
        StringBuilder token = new StringBuilder();
        boolean inText = false;

        for (int i = 0; i < bytes.length - 2; i++) {
            char c = (char) (bytes[i] & 0xFF);
            char n = (char) (bytes[i + 1] & 0xFF);

            // Detect BT (Begin Text) marker
            if (c == 'B' && n == 'T') {
                inText = true;
                token.setLength(0);
                continue;
            }
            // Detect ET (End Text) marker
            if (c == 'E' && n == 'T') {
                inText = false;
                if (token.length() > 0) {
                    sb.append(token).append("\n");
                    token.setLength(0);
                }
                continue;
            }

            if (inText && c > 32 && c < 127) {
                token.append(c);
            } else if (token.length() > 2) {
                sb.append(token).append("\n");
                token.setLength(0);
            } else {
                token.setLength(0);
            }
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            return "[PDF 解析受限] 当前内置解析器仅支持未压缩的 ASCII PDF。"
                    + "如需支持 CJK/压缩 PDF，请引入 Apache PDFBox 依赖并改用 PDFTextStripper。";
        }
        return result;
    }

    private String parseDocx(MultipartFile file) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            StringBuilder sb = new StringBuilder();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    byte[] bytes = zis.readAllBytes();
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    sb.append(stripTags(xml));
                }
            }
            return sb.toString().trim();
        }
    }

    private String parseZip(MultipartFile file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && isTextFile(name)) {
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
                    if (ext.equals("txt") || ext.equals("md") || ext.equals("csv") || ext.equals("json")
                        || ext.equals("xml") || ext.equals("html") || ext.equals("java") || ext.equals("py")
                        || ext.equals("js") || ext.equals("ts") || ext.equals("go") || ext.equals("sql")) {
                        String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                        sb.append("=== ").append(name).append(" ===\n")
                          .append(content)
                          .append("\n\n");
                    }
                }
            }
        }
        String result = sb.toString();
        return result.isEmpty() ? "(ZIP包内无文本文件)" : result;
    }

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "html", "htm",
            "java", "py", "js", "ts", "go", "sql", "rb", "php",
            "yml", "yaml", "properties", "css", "sh", "kt", "swift"
    );

    private boolean isTextFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase();
        return TEXT_EXTENSIONS.contains(ext);
    }
}
package com.agentloop.rag;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private String parseJson(MultipartFile file) throws Exception {
        String json = new String(file.getBytes(), StandardCharsets.UTF_8);
        return extractJsonText(json);
    }

    private String extractJsonText(String json) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        boolean capture = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                current.append(c);
                continue;
            }
            if (c == '"') {
                if (inString) {
                    // End of string
                    if (capture) {
                        sb.append(current).append("\n");
                    }
                    current = new StringBuilder();
                    capture = false;
                } else {
                    // Start of string
                    inString = true;
                }
                continue;
            }
            if (inString) {
                current.append(c);
                // Capture string content if it looks like prose
                if (current.length() > 10 && !current.toString().matches(".*[{}:\\[\\],].*")) {
                    capture = true;
                }
            }
        }
        return sb.toString().trim();
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
        // Simple PDF text extraction - only works for text-based PDFs
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
        return sb.toString().trim();
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

    private boolean isTextFile(String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
        return ext.matches("txt|md|csv|json|xml|html|htm|java|py|js|ts|go|sql|yml|yaml|properties|css|js|ts");
    }
}
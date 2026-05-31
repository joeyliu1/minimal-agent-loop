package com.agentloop.controller;

import com.agentloop.rag.DocumentParser;
import com.agentloop.rag.IndexingService;
import com.agentloop.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Web UI controller for agent interaction.
 */
@RestController
public class WebController {

    private final AgentService agentService;
    private final IndexingService indexingService;
    private final DocumentParser documentParser;

    public WebController(AgentService agentService, IndexingService indexingService, DocumentParser documentParser) {
        this.agentService = agentService;
        this.indexingService = indexingService;
        this.documentParser = documentParser;
    }

    @GetMapping("/")
    public void index(HttpServletResponse response) throws Exception {
        response.sendRedirect("/index.html");
    }

    @GetMapping("/knowledge")
    public void knowledge(HttpServletResponse response) throws Exception {
        response.sendRedirect("/knowledge.html");
    }

    @PostMapping("/api/chat")
    public Map<String, String> chat(@RequestBody Map<String, Object> request) {
        String message = String.valueOf(request.getOrDefault("message", ""));
        boolean useKnowledgeBase = Boolean.parseBoolean(String.valueOf(request.getOrDefault("useKnowledgeBase", true)));
        String response = agentService.execute(message, useKnowledgeBase);
        return Map.of("response", response);
    }

    @PostMapping("/api/rag/upload")
    public Map<String, Object> ragUpload(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Map.of("status", "error", "message", "请选择文件");
        }
        int success = 0;
        int failed = 0;
        StringBuilder msg = new StringBuilder();
        for (MultipartFile file : files) {
            try {
                String content = documentParser.parse(file);
                if (content != null && !content.isBlank()) {
                    indexingService.addDocument(content, "文件上传: " + file.getOriginalFilename());
                    success++;
                    msg.append("✓ ").append(file.getOriginalFilename()).append("\n");
                } else {
                    failed++;
                    msg.append("✗ ").append(file.getOriginalFilename()).append(" (内容为空)\n");
                }
            } catch (Exception e) {
                failed++;
                msg.append("✗ ").append(file.getOriginalFilename()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return Map.of(
            "status", failed == 0 ? "ok" : "partial",
            "success", success,
            "failed", failed,
            "message", msg.toString()
        );
    }

    @PostMapping("/api/rag/add")
    public Map<String, String> ragAdd(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String source = request.getOrDefault("source", "用户添加");
        indexingService.addDocument(content, source);
        return Map.of("status", "ok", "message", "已添加: " + content);
    }

    @DeleteMapping("/api/rag/clear")
    public Map<String, String> ragClear() {
        indexingService.clear();
        return Map.of("status", "ok", "message", "知识库已清空");
    }

    @DeleteMapping("/api/rag/delete")
    public Map<String, String> ragDelete(@RequestParam("id") String id) {
        indexingService.deleteDocument(id);
        return Map.of("status", "ok", "message", "已删除");
    }

    @GetMapping("/api/rag/list")
    public Map<String, Object> ragList() {
        var docs = indexingService.listDocuments();
        return Map.of("status", "ok", "count", docs.size(), "documents", docs);
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}

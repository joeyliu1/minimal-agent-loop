package com.agentloop.controller;

import com.agentloop.service.AgentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Web UI controller for agent interaction.
 */
@RestController
public class WebController {

    private final AgentService agentService;

    public WebController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/index.html";
    }

    @PostMapping("/api/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String response = agentService.execute(message);
        return Map.of("response", response);
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
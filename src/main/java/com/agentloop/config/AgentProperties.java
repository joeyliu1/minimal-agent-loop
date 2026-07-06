package com.agentloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent configuration properties.
 * Maps to the "agent" prefix in application.yml.
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private int maxSteps = 10;
    private int timeoutSeconds = 120;
    private int stepTimeoutMs = 30_000;
    private int llmRetries = 2;
    private int toolRetries = 2;

    // ── Getters & Setters ───────────────────────────────────────────────

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getStepTimeoutMs() { return stepTimeoutMs; }
    public void setStepTimeoutMs(int stepTimeoutMs) { this.stepTimeoutMs = stepTimeoutMs; }

    public int getLlmRetries() { return llmRetries; }
    public void setLlmRetries(int llmRetries) { this.llmRetries = llmRetries; }

    public int getToolRetries() { return toolRetries; }
    public void setToolRetries(int toolRetries) { this.toolRetries = toolRetries; }

}

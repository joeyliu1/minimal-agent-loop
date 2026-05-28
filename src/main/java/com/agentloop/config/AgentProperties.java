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

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
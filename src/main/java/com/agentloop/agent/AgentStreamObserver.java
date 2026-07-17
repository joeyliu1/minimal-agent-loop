package com.agentloop.agent;

/**
 * Receives observable events from a streaming Agent Loop execution.
 */
public interface AgentStreamObserver {

    AgentStreamObserver NO_OP = new AgentStreamObserver() {};

    default void onState(AgentLoopState state, int step) {}

    default void onToken(String token) {}

    /** Clear partial output before a retry or the next post-tool LLM round. */
    default void onReset() {}
}

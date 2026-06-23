package com.agentloop.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Agent loop state machine.
 * <pre>
 * INIT ──→ THINKING ──→ TOOL_CALLING ──→ THINKING
 *            │                                │
 *            └──→ RESPONDING ──→ FINISHED     │
 *                         ↓                   │
 *                      ERROR ───────────────→ FINISHED
 * </pre>
 */
public enum AgentLoopState {

    INIT,
    THINKING,
    TOOL_CALLING,
    RESPONDING,
    FINISHED,
    ERROR;

    private static final java.util.Map<AgentLoopState, Set<AgentLoopState>> TRANSITIONS =
            java.util.Map.of(
                    INIT,         Set.of(THINKING),
                    THINKING,     Set.of(TOOL_CALLING, RESPONDING, ERROR),
                    TOOL_CALLING, Set.of(THINKING, ERROR),
                    RESPONDING,   Set.of(FINISHED, ERROR),
                    ERROR,        Set.of(FINISHED),
                    FINISHED,     Set.of()
            );

    /**
     * Check whether a transition from {@code from} to {@code to} is legal.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public static void guard(AgentLoopState from, AgentLoopState to) {
        Set<AgentLoopState> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    "Illegal agent state transition: " + from + " -> " + to
            );
        }
    }
}

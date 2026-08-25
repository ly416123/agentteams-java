package io.agentteams.application.api;

/**
 * Isolation level requested for a task sandbox.
 */
public enum SandboxProfile {
    /** Keep the existing in-process task execution path. */
    NONE,
    /** Use a disposable task-scoped runtime with the platform baseline policy. */
    ISOLATED,
    /** Use a disposable task-scoped runtime with the strictest available policy. */
    HARDENED
}

package io.agentteams.application.api;

/**
 * Provider-independent lifecycle state of a task sandbox.
 */
public enum SandboxStatus {
    REQUESTED,
    PROVISIONING,
    READY,
    RUNNING,
    STOPPING,
    DESTROYED,
    FAILED,
    EXPIRED,
    LOST
}

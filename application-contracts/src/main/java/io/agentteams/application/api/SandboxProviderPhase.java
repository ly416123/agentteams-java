package io.agentteams.application.api;

/** Finite provider lifecycle phase exposed across the application boundary. */
public enum SandboxProviderPhase {
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

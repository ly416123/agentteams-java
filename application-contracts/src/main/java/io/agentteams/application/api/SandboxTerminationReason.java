package io.agentteams.application.api;

/**
 * Auditable reason for terminating a task sandbox.
 */
public enum SandboxTerminationReason {
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    LEASE_EXPIRED,
    SANDBOX_LOST,
    SUPERSEDED,
    OPERATOR_CLEANUP
}

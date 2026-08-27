package io.agentteams.controlplane.worker;

public enum WorkerOperationType {
    DRAIN,
    ROLLOUT,
    ROLLBACK,
    TERMINATE
}

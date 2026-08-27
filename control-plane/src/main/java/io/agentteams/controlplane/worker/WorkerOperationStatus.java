package io.agentteams.controlplane.worker;

public enum WorkerOperationStatus {
    PENDING,
    RUNNING,
    DRAINED,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK
}

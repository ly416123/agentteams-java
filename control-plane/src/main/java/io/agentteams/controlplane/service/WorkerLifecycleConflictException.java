package io.agentteams.controlplane.service;

public final class WorkerLifecycleConflictException extends RuntimeException {
    private final String code;

    public WorkerLifecycleConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

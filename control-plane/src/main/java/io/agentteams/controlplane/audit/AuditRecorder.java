package io.agentteams.controlplane.audit;

@FunctionalInterface
public interface AuditRecorder {
    void record(AuditEvent event);
}

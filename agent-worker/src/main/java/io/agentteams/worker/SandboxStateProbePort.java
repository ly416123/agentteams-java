package io.agentteams.worker;

import io.agentteams.application.api.SandboxStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-only worker boundary for observing a task sandbox projection. */
@FunctionalInterface
public interface SandboxStateProbePort {
    SandboxExecutionState inspect(UUID sandboxId, UUID taskId, UUID attemptId);

    record SandboxExecutionState(SandboxStatus status, String endpointRef, Instant expiresAt) {
        public SandboxExecutionState {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (endpointRef != null && endpointRef.isBlank()) {
                throw new IllegalArgumentException("endpointRef must be null or non-blank");
            }
        }
    }
}

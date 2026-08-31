package io.agentteams.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application boundary for safe, replayable observations emitted by a Worker.
 *
 * <p>The observation stream is deliberately separate from the task state
 * transition port. A duplicate observation must be harmless and an
 * observation must never carry raw prompts, credentials, or chain-of-thought.
 */
public interface TaskExecutionObservationPort {

    void accepted(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId);

    void progress(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            int percent, String status, String message);

    void completed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String resultSummary, List<ExecutionEventPort.ArtifactReference> artifacts);

    void failed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId,
            String failureCode, String failureMessage);

    static TaskExecutionObservationPort noop() {
        return new TaskExecutionObservationPort() {
            @Override
            public void accepted(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId) {
            }

            @Override
            public void progress(UUID taskId, UUID runId, UUID eventId, Instant occurredAt,
                    String correlationId, int percent, String status, String message) {
            }

            @Override
            public void completed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt,
                    String correlationId, String resultSummary, List<ExecutionEventPort.ArtifactReference> artifacts) {
            }

            @Override
            public void failed(UUID taskId, UUID runId, UUID eventId, Instant occurredAt,
                    String correlationId, String failureCode, String failureMessage) {
            }
        };
    }

    static void requireCommon(UUID taskId, UUID runId, UUID eventId, Instant occurredAt, String correlationId) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }
}

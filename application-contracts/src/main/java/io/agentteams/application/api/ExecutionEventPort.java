package io.agentteams.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for Agent execution events and lease renewals. */
public interface ExecutionEventPort {

    void apply(UUID taskId, TaskExecutionCommand command, List<ArtifactReference> artifacts);

    void renewLease(UUID taskId, LeaseRenewalCommand command);

    enum ExecutionPhase {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    record TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
            Instant occurredAt, String agentId, String source, ExecutionPhase phase,
            String failureCode, String failureMessage, String correlationId, String traceparent,
            String tracestate) {
        public TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, ExecutionPhase phase,
                String failureCode, String failureMessage) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, phase,
                    failureCode, failureMessage, "unknown", "", "");
        }

        public TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, ExecutionPhase phase,
                String failureCode, String failureMessage, String correlationId) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, phase,
                    failureCode, failureMessage, correlationId, "", "");
        }

        public TaskExecutionCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(agentId, "agentId");
            requireText(source, "source");
            Objects.requireNonNull(phase, "phase");
            TraceContext context = new TraceContext(correlationId, traceparent, tracestate);
            correlationId = context.correlationId();
            traceparent = context.traceparent();
            tracestate = context.tracestate();
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            failureCode = failureCode == null || failureCode.isBlank() ? "RUNTIME_FAILURE" : failureCode;
            failureMessage = FailureMessageSanitizer.redact(failureMessage);
        }
    }

    record LeaseRenewalCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
            Instant occurredAt, Instant requestedExpiry, String agentId, String source, String correlationId,
            String traceparent, String tracestate) {
        public LeaseRenewalCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, Instant requestedExpiry, String agentId, String source) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, requestedExpiry, agentId, source,
                    "unknown", "", "");
        }

        public LeaseRenewalCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, Instant requestedExpiry, String agentId, String source, String correlationId) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, requestedExpiry, agentId, source,
                    correlationId, "", "");
        }

        public LeaseRenewalCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(requestedExpiry, "requestedExpiry");
            requireText(agentId, "agentId");
            requireText(source, "source");
            TraceContext context = new TraceContext(correlationId, traceparent, tracestate);
            correlationId = context.correlationId();
            traceparent = context.traceparent();
            tracestate = context.tracestate();
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
        }
    }

    record ArtifactReference(String name, String storageKey, String contentType,
            long sizeBytes, String sha256, String metadataJson) {
        public ArtifactReference {
            requireText(name, "name");
            requireText(storageKey, "storageKey");
            contentType = contentType == null || contentType.isBlank()
                    ? "application/octet-stream" : contentType;
            requireText(sha256, "sha256");
            metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

}

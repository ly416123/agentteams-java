package io.agentteams.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for Agent execution events and lease renewals. */
public interface ExecutionEventPort {

    void apply(UUID taskId, TaskExecutionCommand command, List<ArtifactReference> artifacts);

    void renewLease(UUID taskId, LeaseRenewalCommand command);

    /**
     * Reports a runtime that rejected an assignment (accepted=false). The Control
     * Plane must reclaim the attempt immediately so the task is requeued instead of
     * waiting for the lease to expire naturally.
     */
    void rejectUnaccepted(UUID taskId, RejectionCommand command);

    enum ExecutionPhase {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    record TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
            Instant occurredAt, String agentId, String source, ExecutionPhase phase,
            String failureCode, String failureMessage, String correlationId, String traceparent,
            String tracestate, ModelCallUsage modelCallUsage) {
        public TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, ExecutionPhase phase,
                String failureCode, String failureMessage) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, phase,
                    failureCode, failureMessage, "unknown", "", "", null);
        }

        public TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, ExecutionPhase phase,
                String failureCode, String failureMessage, String correlationId) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, phase,
                    failureCode, failureMessage, correlationId, "", "", null);
        }

        /** Compatibility constructor for callers that already carry trace fields. */
        public TaskExecutionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, ExecutionPhase phase,
                String failureCode, String failureMessage, String correlationId, String traceparent,
                String tracestate) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, phase,
                    failureCode, failureMessage, correlationId, traceparent, tracestate, null);
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

    /** Safe runtime usage metadata persisted for Dashboard/Usage aggregation. */
    record ModelCallUsage(String provider, String model, long latencyMillis, long promptTokens,
            long completionTokens, String tenantId, String projectId, String workerId,
            String taskId, String teamId, String toolId, String quotaId, String quotaDimension) {
        public ModelCallUsage {
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            if (latencyMillis < 0 || promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("runtime usage values must not be negative");
            }
            if ((tenantId == null) != (projectId == null)) {
                throw new IllegalArgumentException("tenantId and projectId must be supplied together");
            }
            tenantId = optionalText(tenantId);
            projectId = optionalText(projectId);
            workerId = optionalText(workerId);
            taskId = optionalText(taskId);
            teamId = optionalText(teamId);
            toolId = optionalText(toolId);
            quotaId = optionalText(quotaId);
            quotaDimension = optionalText(quotaDimension);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }

        private static String optionalText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
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

    /** A runtime rejected a delivered assignment; the assigned attempt must be reclaimed. */
    record RejectionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
            Instant occurredAt, String agentId, String source, String rejectionReason, String correlationId,
            String traceparent, String tracestate) {
        public RejectionCommand(UUID eventId, long expectedVersion, UUID attemptId, UUID leaseId,
                Instant occurredAt, String agentId, String source, String rejectionReason) {
            this(eventId, expectedVersion, attemptId, leaseId, occurredAt, agentId, source, rejectionReason,
                    "unknown", "", "");
        }

        public RejectionCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(agentId, "agentId");
            requireText(source, "source");
            requireText(rejectionReason, "rejectionReason");
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

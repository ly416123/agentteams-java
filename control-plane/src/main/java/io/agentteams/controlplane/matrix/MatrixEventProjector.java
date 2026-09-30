package io.agentteams.controlplane.matrix;

import io.agentteams.application.api.UnifiedEventEnvelope;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

/** Projects domain notifications asynchronously; Matrix delivery never participates in the task transaction. */
public final class MatrixEventProjector {
    private final MatrixRoomBindingRepository bindings;
    private final MatrixOutboundRepository outbound;
    private final Clock clock;

    public MatrixEventProjector(MatrixOutboundRepository outbound, Clock clock) {
        this(null, outbound, clock);
    }

    public MatrixEventProjector(MatrixRoomBindingRepository bindings, MatrixOutboundRepository outbound, Clock clock) {
        this.bindings = bindings;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public java.util.UUID project(String roomId, String eventType, String body) {
        return outbound.enqueue(roomId, eventType, body, clock.instant());
    }

    public ProjectionResult project(UnifiedEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        if ("INTERNAL".equalsIgnoreCase(event.visibility())) {
            return ProjectionResult.skipped();
        }
        if (bindings == null) {
            throw new IllegalStateException("Matrix room bindings are not configured");
        }
        Optional<MatrixRoomBinding> binding = resolve(event);
        if (binding.isEmpty()) return ProjectionResult.skipped();
        String roomId = binding.get().roomId();
        UUID outboxId = UUID.nameUUIDFromBytes((event.eventId() + ":" + roomId)
                .getBytes(StandardCharsets.UTF_8));
        MatrixRoomBinding roomBinding = binding.orElseThrow();
        boolean queued = outbound.enqueue(outboxId, roomBinding.tenantId(), roomBinding.id(), roomId,
                event.eventType(), safeSummary(event), clock.instant());
        return new ProjectionResult(queued ? ProjectionStatus.QUEUED : ProjectionStatus.DUPLICATE, roomId, outboxId);
    }

    private Optional<MatrixRoomBinding> resolve(UnifiedEventEnvelope event) {
        if (event.taskId() != null) {
            Optional<MatrixRoomBinding> task = bindings.findActiveByContext(event.tenantId(),
                    MatrixRoomContextType.TASK, event.taskId());
            if (task.isPresent()) return task;
        }
        if ("TASK".equalsIgnoreCase(event.aggregateType()) && event.aggregateId() != null) {
            Optional<MatrixRoomBinding> aggregateTask = bindings.findActiveByContext(event.tenantId(),
                    MatrixRoomContextType.TASK, event.aggregateId());
            if (aggregateTask.isPresent()) return aggregateTask;
        }
        if (event.conversationId() != null) {
            return bindings.findActiveByContext(event.tenantId(), MatrixRoomContextType.CONVERSATION,
                    event.conversationId());
        }
        if ("CONVERSATION".equalsIgnoreCase(event.aggregateType())) {
            return bindings.findActiveByContext(event.tenantId(), MatrixRoomContextType.CONVERSATION,
                    event.aggregateId());
        }
        return Optional.empty();
    }

    private static String safeSummary(UnifiedEventEnvelope event) {
        StringBuilder summary = new StringBuilder(event.eventType())
                .append(" ").append(event.aggregateType()).append(" ").append(event.aggregateId())
                .append(" v").append(event.aggregateVersion());
        String payloadRef = event.payload().path("payloadRef").asText(null);
        if (payloadRef != null && !payloadRef.isBlank()) summary.append(" ").append(payloadRef);
        return summary.toString();
    }

    public enum ProjectionStatus { QUEUED, DUPLICATE, SKIPPED }

    public record ProjectionResult(ProjectionStatus status, String roomId, UUID outboxId) {
        static ProjectionResult skipped() {
            return new ProjectionResult(ProjectionStatus.SKIPPED, null, null);
        }
    }
}

package io.agentteams.runtime;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges the push protocol to a runtime without putting runtime code into the Gateway. */
public final class GatewayRuntimeAdapter {
    private final String agentId;
    private final AgentChannelPort channel;
    private final AgentRuntime runtime;
    private final Clock clock;
    private final Map<UUID, AssignmentContext> assignments = new ConcurrentHashMap<>();

    public GatewayRuntimeAdapter(String agentId, AgentChannelPort channel, AgentRuntime runtime, Clock clock) {
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
        this.agentId = agentId;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RuntimeSubmission acceptAssignment(TaskAssigned assignment) {
        Objects.requireNonNull(assignment, "assignment");
        EventMetadata input = assignment.getMetadata();
        UUID taskId = uuid(input.getTaskId(), "task_id");
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("agentId", agentId);
        metadata.put("attemptId", input.getAttemptId());
        metadata.put("leaseId", input.getLeaseId());
        putIfPresent(metadata, "tenantId", assignment.getTenantId());
        putIfPresent(metadata, "projectId", assignment.getProjectId());
        putIfPresent(metadata, "teamId", assignment.getTeamId());
        putIfPresent(metadata, "toolId", assignment.getToolId());
        putIfPresent(metadata, "quotaId", assignment.getQuotaId());
        putIfPresent(metadata, "quotaDimension", assignment.getQuotaDimension());
        RuntimeTask task = new RuntimeTask(taskId, assignment.getTaskType(),
                assignment.getInputJson().toStringUtf8(), metadata);
        AssignmentContext assignmentContext = new AssignmentContext(input, assignment.getLeaseExpiresAt());
        AssignmentContext existing = assignments.putIfAbsent(taskId, assignmentContext);
        boolean registered = existing == null;
        if (existing != null && existing.matches(input)) {
            channel.send(AgentMessage.newBuilder().setTaskAccepted(TaskAccepted.newBuilder()
                    .setMetadata(metadata(taskId, existing)).setAccepted(true)
                    .setRejectionReason("").build()).build());
            return RuntimeSubmission.duplicateAccepted();
        }
        if (existing != null) {
            // A later assignment is a retry after the previous lease expired.
            // Cancel any still-running local work and replace the assignment
            // context before submitting the new attempt.
            runtime.cancel(taskId);
            assignments.replace(taskId, existing, assignmentContext);
            registered = true;
        }
        RuntimeSubmission submission;
        try {
            submission = runtime.submit(task);
        } catch (RuntimeException error) {
            if (registered) assignments.remove(taskId, assignmentContext);
            throw error;
        }
        if (!submission.accepted() && registered) assignments.remove(taskId, assignmentContext);
        channel.send(AgentMessage.newBuilder().setTaskAccepted(TaskAccepted.newBuilder()
                .setMetadata(metadata(taskId, assignmentContext)).setAccepted(submission.accepted())
                .setRejectionReason(submission.accepted() ? "" : submission.reason()).build()).build());
        if (submission.accepted()) {
            assignmentContext.advanceVersion();
        }
        return submission;
    }

    public void progress(UUID taskId, int percent, String status, String message) {
        AssignmentContext context = context(taskId);
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent must be 0..100");
        channel.send(AgentMessage.newBuilder().setTaskProgress(TaskProgress.newBuilder()
                .setMetadata(metadata(taskId, context)).setPercent(percent)
                .setStatus(status == null ? "" : status).setMessage(message == null ? "" : message).build()).build());
        context.advanceVersion();
    }

    public void heartbeat(UUID taskId, String status) {
        AssignmentContext context = context(taskId);
        channel.send(AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                .setMetadata(metadata(taskId, context)).setStatus(status == null ? "" : status)
                .setLeaseExpiresAt(context.leaseExpiresAt()).build()).build());
        context.advanceVersion();
    }

    /** Keeps terminal execution events aligned after a heartbeat sent by the channel client. */
    public void onHeartbeatSent(UUID taskId) {
        context(taskId).advanceVersion();
    }

    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        CompletionStatus status = runtime.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            AssignmentContext context = context(result.taskId());
            EventMetadata eventMetadata = metadata(result.taskId(), context);
            AgentMessage message = result.success()
                    ? AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                            .setMetadata(eventMetadata)
                            .setResultJson(ByteString.copyFromUtf8(result.output())).build()).build()
                    : AgentMessage.newBuilder().setTaskFailed(TaskFailed.newBuilder()
                            .setMetadata(eventMetadata)
                            .setCode("RUNTIME_FAILURE")
                            .setMessage(result.output())
                            .setRetryable(false).build()).build();
            channel.send(message);
            assignments.remove(result.taskId());
        }
        return status;
    }

    public CompletionStatus fail(UUID taskId, String code, String message, boolean retryable) {
        CompletionStatus status = runtime.complete(RuntimeResult.failure(taskId, message == null ? "" : message,
                clock.instant()));
        if (status == CompletionStatus.COMPLETED) {
            AssignmentContext context = context(taskId);
            channel.send(AgentMessage.newBuilder().setTaskFailed(TaskFailed.newBuilder()
                    .setMetadata(metadata(taskId, context)).setCode(code == null ? "RUNTIME_FAILURE" : code)
                    .setMessage(message == null ? "" : message).setRetryable(retryable).build()).build());
            assignments.remove(taskId);
        }
        return status;
    }

    private AssignmentContext context(UUID taskId) {
        return Objects.requireNonNull(assignments.get(taskId), "unknown task assignment: " + taskId);
    }

    private EventMetadata metadata(UUID taskId, AssignmentContext context) {
        return context.metadata().toBuilder().setEventId(UUID.randomUUID().toString()).setAgentId(agentId)
                .setTaskId(taskId.toString()).setExpectedVersion(context.expectedVersion())
                .setOccurredAt(timestamp(clock.instant())).build();
    }

    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value); }
        catch (Exception error) { throw new IllegalArgumentException(field + " must be a UUID", error); }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static final class AssignmentContext {
        private final EventMetadata metadata;
        private final Timestamp leaseExpiresAt;
        private long expectedVersion;

        private AssignmentContext(EventMetadata metadata, Timestamp leaseExpiresAt) {
            this.metadata = metadata;
            this.leaseExpiresAt = leaseExpiresAt;
            this.expectedVersion = metadata.getExpectedVersion();
        }

        private synchronized EventMetadata metadata() {
            return metadata;
        }

        private synchronized Timestamp leaseExpiresAt() {
            return leaseExpiresAt;
        }

        private synchronized long expectedVersion() {
            return expectedVersion;
        }

        private synchronized void advanceVersion() {
            expectedVersion = Math.addExact(expectedVersion, 1);
        }

        private boolean matches(EventMetadata input) {
            return metadata.getAttemptId().equals(input.getAttemptId())
                    && metadata.getLeaseId().equals(input.getLeaseId());
        }
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }
}

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
        RuntimeTask task = new RuntimeTask(taskId, assignment.getTaskType(),
                assignment.getInputJson().toStringUtf8(), Map.of("agentId", agentId,
                        "attemptId", input.getAttemptId(), "leaseId", input.getLeaseId()));
        RuntimeSubmission submission = runtime.submit(task);
        if (submission.accepted()) assignments.putIfAbsent(taskId,
                new AssignmentContext(input, assignment.getLeaseExpiresAt()));
        channel.send(AgentMessage.newBuilder().setTaskAccepted(TaskAccepted.newBuilder()
                .setMetadata(metadata(taskId, input)).setAccepted(submission.accepted())
                .setRejectionReason(submission.accepted() ? "" : submission.reason()).build()).build());
        return submission;
    }

    public void progress(UUID taskId, int percent, String status, String message) {
        AssignmentContext context = context(taskId);
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent must be 0..100");
        channel.send(AgentMessage.newBuilder().setTaskProgress(TaskProgress.newBuilder()
                .setMetadata(metadata(taskId, context.metadata())).setPercent(percent)
                .setStatus(status == null ? "" : status).setMessage(message == null ? "" : message).build()).build());
    }

    public void heartbeat(UUID taskId, String status) {
        AssignmentContext context = context(taskId);
        channel.send(AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                .setMetadata(metadata(taskId, context.metadata())).setStatus(status == null ? "" : status)
                .setLeaseExpiresAt(context.leaseExpiresAt()).build()).build());
    }

    public CompletionStatus complete(RuntimeResult result) {
        Objects.requireNonNull(result, "result");
        CompletionStatus status = runtime.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            AssignmentContext context = context(result.taskId());
            channel.send(AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                    .setMetadata(metadata(result.taskId(), context.metadata()))
                    .setResultJson(ByteString.copyFromUtf8(result.output())).build()).build());
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
                    .setMetadata(metadata(taskId, context.metadata())).setCode(code == null ? "RUNTIME_FAILURE" : code)
                    .setMessage(message == null ? "" : message).setRetryable(retryable).build()).build());
            assignments.remove(taskId);
        }
        return status;
    }

    private AssignmentContext context(UUID taskId) {
        return Objects.requireNonNull(assignments.get(taskId), "unknown task assignment: " + taskId);
    }

    private EventMetadata metadata(UUID taskId, EventMetadata input) {
        return input.toBuilder().setEventId(UUID.randomUUID().toString()).setAgentId(agentId)
                .setTaskId(taskId.toString()).setOccurredAt(timestamp(clock.instant())).build();
    }

    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value); }
        catch (Exception error) { throw new IllegalArgumentException(field + " must be a UUID", error); }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private record AssignmentContext(EventMetadata metadata, Timestamp leaseExpiresAt) { }
}

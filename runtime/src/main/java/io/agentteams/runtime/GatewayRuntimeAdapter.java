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
import io.agentteams.contracts.v1.SandboxAssignment;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        if (assignment.hasSandbox()) {
            addSandboxMetadata(metadata, assignment.getSandbox(), taskId, input.getAttemptId());
        }
        RuntimeTask task = new RuntimeTask(taskId, assignment.getTaskType(),
                assignment.getInputJson().toStringUtf8(), metadata);
        AssignmentContext assignmentContext = new AssignmentContext(input, assignment.getLeaseExpiresAt(),
                assignment.getTenantId(), assignment.getProjectId(), assignment.getTeamId(),
                assignment.getToolId(), assignment.getQuotaId(), assignment.getQuotaDimension());
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
            io.agentteams.contracts.v1.ModelCallUsage usage = modelCallUsage(result, context);
            AgentMessage.Builder message = AgentMessage.newBuilder();
            if (result.success()) {
                TaskCompleted.Builder completed = TaskCompleted.newBuilder().setMetadata(eventMetadata)
                        .setResultJson(ByteString.copyFromUtf8(result.output()));
                if (usage != null) completed.setModelCall(usage);
                message.setTaskCompleted(completed);
            } else {
                TaskFailed.Builder failed = TaskFailed.newBuilder().setMetadata(eventMetadata)
                        .setCode("RUNTIME_FAILURE").setMessage(result.output()).setRetryable(false);
                if (usage != null) failed.setModelCall(usage);
                message.setTaskFailed(failed);
            }
            AgentMessage outbound = message.build();
            channel.send(outbound);
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
        private final String tenantId;
        private final String projectId;
        private final String teamId;
        private final String toolId;
        private final String quotaId;
        private final String quotaDimension;
        private long expectedVersion;

        private AssignmentContext(EventMetadata metadata, Timestamp leaseExpiresAt, String tenantId,
                String projectId, String teamId, String toolId, String quotaId, String quotaDimension) {
            this.metadata = metadata;
            this.leaseExpiresAt = leaseExpiresAt;
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.teamId = teamId;
            this.toolId = toolId;
            this.quotaId = quotaId;
            this.quotaDimension = quotaDimension;
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

    private static io.agentteams.contracts.v1.ModelCallUsage modelCallUsage(RuntimeResult result,
            AssignmentContext context) {
        RuntimeCallUsage usage = result.callUsage();
        if (usage == null) return null;
        return io.agentteams.contracts.v1.ModelCallUsage.newBuilder()
                .setProvider(usage.provider()).setModel(usage.model()).setLatencyMillis(usage.latencyMillis())
                .setPromptTokens(usage.promptTokens()).setCompletionTokens(usage.completionTokens())
                .setTenantId(context.tenantId == null ? "" : context.tenantId)
                .setProjectId(context.projectId == null ? "" : context.projectId)
                .setWorkerId(context.metadata.getAgentId()).setTaskId(result.taskId().toString())
                .setTeamId(context.teamId == null ? "" : context.teamId)
                .setToolId(context.toolId == null ? "" : context.toolId)
                .setQuotaId(context.quotaId == null ? "" : context.quotaId)
                .setQuotaDimension(context.quotaDimension == null ? "" : context.quotaDimension)
                .build();
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private void addSandboxMetadata(Map<String, String> metadata, SandboxAssignment sandbox,
            UUID taskId, String topLevelAttemptId) {
        String ownerTaskId = required(sandbox.getOwnerTaskId(), "sandbox.ownerTaskId");
        String ownerAttemptId = required(sandbox.getOwnerAttemptId(), "sandbox.ownerAttemptId");
        if (!taskId.toString().equals(ownerTaskId) || !topLevelAttemptId.equals(ownerAttemptId)) {
            throw new IllegalArgumentException("sandbox owner does not match task assignment");
        }
        String sandboxId = bounded(required(sandbox.getSandboxId(), "sandbox.sandboxId"), "sandboxId");
        String providerSandboxId = bounded(required(sandbox.getProviderSandboxId(), "sandbox.providerSandboxId"),
                "providerSandboxId");
        String profile = bounded(required(sandbox.getProfile(), "sandbox.profile"), "profile").toUpperCase();
        if (!"ISOLATED".equals(profile) && !"HARDENED".equals(profile)) {
            throw new IllegalArgumentException("sandbox profile is not executable");
        }
        String status = bounded(required(sandbox.getStatus(), "sandbox.status"), "status").toUpperCase();
        if (!"READY".equals(status) && !"RUNNING".equals(status)) {
            throw new IllegalArgumentException("sandbox status is not executable");
        }
        Instant expiresAt = sandboxExpiresAt(sandbox);
        if (!clock.instant().isBefore(expiresAt)) {
            throw new IllegalArgumentException("sandbox assignment is expired");
        }
        String endpointRef = validateEndpoint(required(sandbox.getEndpointRef(), "sandbox.endpointRef"));
        metadata.put("sandboxId", sandboxId);
        metadata.put("providerSandboxId", providerSandboxId);
        metadata.put("profile", profile);
        metadata.put("status", status);
        metadata.put("endpointRef", endpointRef);
        metadata.put("expiresAt", expiresAt.toString());
        metadata.put("ownerTaskId", ownerTaskId);
        metadata.put("ownerAttemptId", ownerAttemptId);
    }

    private static Instant sandboxExpiresAt(SandboxAssignment sandbox) {
        if (!sandbox.hasExpiresAt()) {
            throw new IllegalArgumentException("sandbox.expiresAt must be present");
        }
        try {
            return Instant.ofEpochSecond(sandbox.getExpiresAt().getSeconds(), sandbox.getExpiresAt().getNanos());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("sandbox.expiresAt is invalid", error);
        }
    }

    private static String validateEndpoint(String endpointRef) {
        final URI uri;
        try {
            uri = new URI(endpointRef);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("sandbox.endpointRef is not a URI", error);
        }
        if ("sandbox".equalsIgnoreCase(uri.getScheme())) {
            if (uri.isOpaque() || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null || uri.getRawPath().isBlank()
                    || containsTraversal(uri.getPath())) {
                throw new IllegalArgumentException("sandbox.endpointRef is not controlled");
            }
            return uri.toString();
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            if (uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPath() == null || !uri.getPath().startsWith("/")
                    || containsTraversal(uri.getPath())
                    || "docker.sock".equals(Path.of(uri.getPath()).getFileName().toString())) {
                throw new IllegalArgumentException("sandbox.endpointRef is not a controlled file path");
            }
            return uri.toString();
        }
        throw new IllegalArgumentException("sandbox.endpointRef scheme is not allowed");
    }

    private static boolean containsTraversal(String path) {
        for (Path part : Paths.get(path)) {
            if ("..".equals(part.toString())) return true;
        }
        return false;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String bounded(String value, String field) {
        if (value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}

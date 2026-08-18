package io.agentteams.runtime;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.AgentReady;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.contracts.v1.TaskHeartbeat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In-memory client state machine for the AgentChannel protocol. A transport is
 * deliberately represented by AgentChannelPort so tests and runtimes can inject
 * a real stream later without changing lifecycle semantics.
 */
public final class AgentChannelClient {
    private final String agentId;
    private final AgentChannelPort channel;
    private final Clock clock;
    private final Duration reconnectDelay;
    private final Map<UUID, AgentLease> leases = new HashMap<>();
    private AgentChannelState state = AgentChannelState.DISCONNECTED;
    private Instant reconnectAt;

    public AgentChannelClient(String agentId, AgentChannelPort channel, Clock clock, Duration reconnectDelay) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        this.agentId = agentId;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (reconnectDelay == null || reconnectDelay.isZero() || reconnectDelay.isNegative()) {
            throw new IllegalArgumentException("reconnectDelay must be positive");
        }
        this.reconnectDelay = reconnectDelay;
    }

    public synchronized AgentChannelState state() {
        return state;
    }

    public synchronized void connect(AgentHello hello) {
        Objects.requireNonNull(hello, "hello");
        requireHelloAgent(hello);
        if (state != AgentChannelState.DISCONNECTED) {
            throw new IllegalStateException("cannot connect from " + state);
        }
        state = AgentChannelState.CONNECTING;
        channel.send(AgentMessage.newBuilder().setHello(hello).build());
    }

    public synchronized void onReady(AgentReady ready) {
        Objects.requireNonNull(ready, "ready");
        if (state != AgentChannelState.CONNECTING) {
            throw new IllegalStateException("cannot receive ready from " + state);
        }
        state = ready.getAccepted() ? AgentChannelState.READY : AgentChannelState.REJECTED;
        reconnectAt = null;
    }

    public synchronized void onDisconnected() {
        if (state == AgentChannelState.READY || state == AgentChannelState.CONNECTING) {
            state = AgentChannelState.RECONNECTING;
            reconnectAt = clock.instant().plus(reconnectDelay);
        }
    }

    public synchronized boolean reconnectIfDue(AgentHello hello) {
        Objects.requireNonNull(hello, "hello");
        requireHelloAgent(hello);
        if (state != AgentChannelState.RECONNECTING || reconnectAt == null
                || clock.instant().isBefore(reconnectAt)) {
            return false;
        }
        state = AgentChannelState.CONNECTING;
        reconnectAt = null;
        channel.send(AgentMessage.newBuilder().setHello(hello).build());
        return true;
    }

    public synchronized void registerLease(AgentLease lease) {
        Objects.requireNonNull(lease, "lease");
        leases.put(lease.taskId(), lease);
    }

    public synchronized void releaseLease(UUID taskId) {
        leases.remove(Objects.requireNonNull(taskId, "taskId"));
    }

    /** Delivers a server assignment into the runtime only after the channel is Ready. */
    public synchronized RuntimeSubmission onTaskAssigned(TaskAssigned assignment,
            GatewayRuntimeAdapter runtimeAdapter) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        if (state != AgentChannelState.READY) {
            throw new IllegalStateException("cannot accept task assignment from " + state);
        }
        if (!assignment.hasMetadata()) {
            throw new IllegalArgumentException("task assignment metadata is required");
        }
        EventMetadata metadata = assignment.getMetadata();
        if (!agentId.equals(metadata.getAgentId())) {
            throw new IllegalArgumentException("task assignment agent_id does not match client agentId");
        }
        UUID taskId = uuid(metadata.getTaskId(), "task_id");
        if (metadata.getAttemptId().isBlank() || metadata.getLeaseId().isBlank()) {
            throw new IllegalArgumentException("task assignment attempt_id and lease_id are required");
        }
        Instant expiresAt = Instant.ofEpochSecond(assignment.getLeaseExpiresAt().getSeconds(),
                assignment.getLeaseExpiresAt().getNanos());
        if (!expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("task assignment lease is already expired");
        }
        AgentLease lease = new AgentLease(taskId, metadata.getAttemptId(), metadata.getLeaseId(), expiresAt);
        registerLease(lease);
        try {
            RuntimeSubmission submission = runtimeAdapter.acceptAssignment(assignment);
            if (!submission.accepted()) {
                releaseLease(taskId);
            }
            return submission;
        } catch (RuntimeException error) {
            releaseLease(taskId);
            throw error;
        }
    }

    /** Completes a runtime task and releases its channel lease after durable delivery. */
    public synchronized CompletionStatus completeTask(RuntimeResult result,
            GatewayRuntimeAdapter runtimeAdapter) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        CompletionStatus status = runtimeAdapter.complete(result);
        if (status == CompletionStatus.COMPLETED) {
            releaseLease(result.taskId());
        }
        return status;
    }

    /** Reports a runtime failure and releases its channel lease after durable delivery. */
    public synchronized CompletionStatus failTask(UUID taskId, String code, String message, boolean retryable,
            GatewayRuntimeAdapter runtimeAdapter) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        CompletionStatus status = runtimeAdapter.fail(taskId, code, message, retryable);
        if (status == CompletionStatus.COMPLETED) {
            releaseLease(taskId);
        }
        return status;
    }

    public synchronized HeartbeatResult heartbeat(UUID taskId, String status) {
        Objects.requireNonNull(taskId, "taskId");
        if (state != AgentChannelState.READY) {
            return HeartbeatResult.NOT_READY;
        }
        AgentLease lease = leases.get(taskId);
        if (lease == null) {
            return HeartbeatResult.UNKNOWN_LEASE;
        }
        Instant now = clock.instant();
        if (!now.isBefore(lease.expiresAt())) {
            return HeartbeatResult.EXPIRED;
        }
        EventMetadata metadata = EventMetadata.newBuilder().setEventId(UUID.randomUUID().toString())
                .setAgentId(agentId).setTaskId(taskId.toString()).setAttemptId(lease.attemptId())
                .setLeaseId(lease.leaseId()).setOccurredAt(timestamp(now)).build();
        channel.send(AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                .setMetadata(metadata).setStatus(status == null ? "" : status)
                .setLeaseExpiresAt(timestamp(lease.expiresAt())).build()).build());
        return HeartbeatResult.SENT;
    }

    public synchronized void close() {
        state = AgentChannelState.CLOSED;
        reconnectAt = null;
    }

    private void requireHelloAgent(AgentHello hello) {
        if (!hello.hasMetadata() || hello.getMetadata().getAgentId().isBlank()) {
            throw new IllegalArgumentException("hello metadata agent_id is required");
        }
        String helloAgentId = hello.getMetadata().getAgentId();
        if (!agentId.equals(helloAgentId)) {
            throw new IllegalArgumentException("hello agent_id does not match client agentId");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}

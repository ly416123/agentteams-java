package io.agentteams.gateway;

import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A stream handle and its connection-local state; it contains no task state. */
public final class AgentConnection {

    private final UUID connectionId;
    private final StreamObserver<ServerMessage> outbound;
    private final String transportIdentity;
    private final Instant connectedAt;
    private final AtomicLong lastAckSequence = new AtomicLong();
    private volatile AgentProfile profile;
    private volatile Instant lastSeen;

    AgentConnection(UUID connectionId, StreamObserver<ServerMessage> outbound,
            String transportIdentity, Instant connectedAt) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.transportIdentity = Objects.requireNonNull(transportIdentity, "transportIdentity");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        this.lastSeen = connectedAt;
    }

    public UUID connectionId() {
        return connectionId;
    }

    public StreamObserver<ServerMessage> outbound() {
        return outbound;
    }

    public String transportIdentity() {
        return transportIdentity;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public Optional<AgentProfile> profile() {
        return Optional.ofNullable(profile);
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public String specDigest() {
        return profile == null ? "" : profile.specDigest();
    }

    public String configRevision() {
        return profile == null ? "" : profile.configRevision();
    }

    public String secretGeneration() {
        return profile == null ? "" : profile.secretGeneration();
    }

    public long lastAckSequence() {
        return lastAckSequence.get();
    }

    void register(AgentProfile nextProfile, long initialAckSequence, Instant seenAt) {
        if (initialAckSequence < 0) {
            throw new IllegalArgumentException("initialAckSequence must not be negative");
        }
        if (profile != null) {
            throw new IllegalStateException("connection is already registered");
        }
        profile = Objects.requireNonNull(nextProfile, "nextProfile");
        lastAckSequence.set(initialAckSequence);
        lastSeen = Objects.requireNonNull(seenAt, "seenAt");
    }

    void seenAt(Instant at) {
        lastSeen = Objects.requireNonNull(at, "at");
    }

    void acknowledge(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        lastAckSequence.accumulateAndGet(sequence, Math::max);
    }
}

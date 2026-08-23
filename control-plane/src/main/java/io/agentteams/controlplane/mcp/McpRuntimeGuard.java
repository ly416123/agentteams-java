package io.agentteams.controlplane.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-server admission guard for MCP network operations.
 *
 * <p>The admission limit is deliberately an in-flight limit rather than a sleeping queue. A
 * caller either gets a lease or a stable rejection immediately. Leases are one-shot and safe to
 * close more than once, which makes every connector completion, timeout and exceptional path
 * release its count.</p>
 */
@Component
public final class McpRuntimeGuard {
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final Clock clock;
    private final int maxConcurrentRequests;
    private final int failureThreshold;
    private final Duration cooldown;
    private final McpObservability observability;
    private final Map<UUID, ServerState> states = new ConcurrentHashMap<>();

    @Autowired
    public McpRuntimeGuard(Clock clock, McpRuntimeGuardProperties properties, McpObservability observability) {
        this(clock, properties.getMaxConcurrentRequests(), properties.getFailureThreshold(), properties.getCooldown(),
                observability);
    }

    public McpRuntimeGuard(Clock clock, int maxConcurrentRequests, int failureThreshold, Duration cooldown) {
        this(clock, maxConcurrentRequests, failureThreshold, cooldown, new McpObservability());
    }

    McpRuntimeGuard(Clock clock, int maxConcurrentRequests, int failureThreshold, Duration cooldown,
            McpObservability observability) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    public McpRuntimeGuard() {
        this(Clock.systemUTC(), 64, 5, Duration.ofSeconds(30));
    }

    /** Compatibility constructor for execution-service tests and embedded callers. */
    public McpRuntimeGuard(Clock clock) {
        this(clock, 64, 5, Duration.ofSeconds(30));
    }

    /** Attempts to reserve one in-flight operation for a server. */
    public Lease tryAcquire(UUID serverId) {
        Objects.requireNonNull(serverId, "serverId");
        ServerState state = states.computeIfAbsent(serverId, ignored -> new ServerState());
        synchronized (state) {
            Instant now = clock.instant();
            if (state.circuit == Circuit.OPEN) {
                if (now.isBefore(state.reopenAt)) {
                    observability.runtimeRejected(CIRCUIT_OPEN);
                    return Lease.rejected(CIRCUIT_OPEN);
                }
                // Only one probe may enter half-open. Existing operations accepted before the
                // open transition are not allowed to become the half-open probe.
                state.circuit = Circuit.HALF_OPEN;
                state.generation++;
            }
            if (state.circuit == Circuit.HALF_OPEN) {
                if (state.halfOpenInFlight || state.inFlight > 0) {
                    observability.runtimeRejected(CIRCUIT_OPEN);
                    return Lease.rejected(CIRCUIT_OPEN);
                }
                state.halfOpenInFlight = true;
            } else if (state.inFlight >= maxConcurrentRequests) {
                observability.runtimeRejected(RATE_LIMITED);
                return Lease.rejected(RATE_LIMITED);
            }
            state.inFlight++;
            return new Lease(this, serverId, state, state.generation, true, null);
        }
    }

    /** Compatibility spelling for callers that model admission as an acquire operation. */
    public Lease acquire(UUID serverId) {
        return tryAcquire(serverId);
    }

    public int inFlight(UUID serverId) {
        Objects.requireNonNull(serverId, "serverId");
        ServerState state = states.get(serverId);
        if (state == null) return 0;
        synchronized (state) {
            return state.inFlight;
        }
    }

    public int trackedServerCount() {
        return states.size();
    }

    private void complete(Lease lease, boolean success) {
        ServerState state = lease.state;
        synchronized (state) {
            if (lease.released) return;
            lease.released = true;
            if (state.inFlight > 0) state.inFlight--;
            if (state.circuit == Circuit.HALF_OPEN && lease.generation == state.generation) {
                state.halfOpenInFlight = false;
                if (success) {
                    state.circuit = Circuit.CLOSED;
                    state.consecutiveFailures = 0;
                    observability.circuitRecovered();
                } else {
                    open(state);
                }
            } else if (state.circuit == Circuit.CLOSED && lease.generation == state.generation) {
                if (success) {
                    state.consecutiveFailures = 0;
                } else if (++state.consecutiveFailures >= failureThreshold) {
                    open(state);
                }
            }
        }
        cleanup(lease.serverId, state);
    }

    private void open(ServerState state) {
        state.circuit = Circuit.OPEN;
        state.reopenAt = clock.instant().plus(cooldown);
        state.consecutiveFailures = 0;
        state.halfOpenInFlight = false;
        state.generation++;
        observability.circuitOpened();
    }

    private void cleanup(UUID serverId, ServerState state) {
        synchronized (state) {
            if (state.inFlight == 0 && state.circuit == Circuit.CLOSED && state.consecutiveFailures == 0) {
                states.remove(serverId, state);
            }
        }
    }

    private enum Circuit { CLOSED, OPEN, HALF_OPEN }

    private static final class ServerState {
        private Circuit circuit = Circuit.CLOSED;
        private Instant reopenAt = Instant.MIN;
        private int inFlight;
        private int consecutiveFailures;
        private boolean halfOpenInFlight;
        private long generation;
    }

    /** A granted lease must be completed; close without a result is treated as a failure. */
    public static final class Lease implements AutoCloseable {
        private final McpRuntimeGuard owner;
        private final UUID serverId;
        private final ServerState state;
        private final long generation;
        private final boolean granted;
        private final String rejection;
        private boolean released;

        private Lease(McpRuntimeGuard owner, UUID serverId, ServerState state, long generation,
                boolean granted, String rejection) {
            this.owner = owner;
            this.serverId = serverId;
            this.state = state;
            this.generation = generation;
            this.granted = granted;
            this.rejection = rejection;
        }

        private static Lease rejected(String rejection) {
            return new Lease(null, null, null, 0, false, rejection);
        }

        public boolean granted() {
            return granted;
        }

        public String rejection() {
            return rejection;
        }

        public void success() {
            if (!granted) return;
            owner.complete(this, true);
        }

        public void failure() {
            if (!granted) return;
            owner.complete(this, false);
        }

        @Override
        public void close() {
            failure();
        }

        public boolean isReleased() {
            return released;
        }
    }
}

package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.util.Objects;

/** Projects domain notifications asynchronously; Matrix delivery never participates in the task transaction. */
public final class MatrixEventProjector {
    private final MatrixOutboundRepository outbound;
    private final Clock clock;

    public MatrixEventProjector(MatrixOutboundRepository outbound, Clock clock) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public java.util.UUID project(String roomId, String eventType, String body) {
        return outbound.enqueue(roomId, eventType, body, clock.instant());
    }
}

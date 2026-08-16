package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class MatrixDeliveryService {
    private final MatrixOutboundRepository outbound;
    private final Clock clock;
    private final Duration retryDelay;

    public MatrixDeliveryService(MatrixOutboundRepository outbound, Clock clock, Duration retryDelay) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        this.retryDelay = retryDelay;
    }

    public int deliver(int limit, MatrixTransport transport) {
        int delivered = 0;
        for (MatrixOutboundMessage message : outbound.claimDue(clock.instant(), limit)) {
            try {
                transport.send(message.roomId(), message.eventType(), message.body());
                outbound.markSent(message.id(), clock.instant());
                delivered++;
            } catch (RuntimeException error) {
                outbound.retry(message.id(), clock.instant().plus(retryDelay),
                        sanitize(error.getMessage()), clock.instant());
            }
        }
        return delivered;
    }

    @FunctionalInterface
    public interface MatrixTransport {
        void send(String roomId, String eventType, String body);
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) return "matrix delivery failed";
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}

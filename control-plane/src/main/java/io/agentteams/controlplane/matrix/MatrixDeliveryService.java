package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

public final class MatrixDeliveryService {
    private final MatrixOutboundRepository outbound;
    private final Clock clock;
    private final Duration retryDelay;
    private final MatrixRoomBindingRepository roomBindings;
    private final TransactionOperations transactions;

    public MatrixDeliveryService(MatrixOutboundRepository outbound, Clock clock, Duration retryDelay,
            MatrixRoomBindingRepository roomBindings, TransactionOperations transactions) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.roomBindings = Objects.requireNonNull(roomBindings, "roomBindings");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        this.retryDelay = retryDelay;
    }

    public int deliver(int limit, MatrixTransport transport) {
        int delivered = 0;
        for (MatrixOutboundMessage message : outbound.claimDue(clock.instant(), limit)) {
            try {
                if (deliverOne(message, transport)) delivered++;
            } catch (MatrixDeliveryException error) {
                if (error.retryable()) {
                    Duration delay = error.retryAfter().orElse(retryDelay);
                    outbound.retry(message.id(), clock.instant().plus(delay), sanitize(error.getMessage()), clock.instant());
                } else {
                    outbound.suppress(message.id(), sanitize(error.getMessage()), clock.instant());
                }
            } catch (RuntimeException error) {
                outbound.retry(message.id(), clock.instant().plus(retryDelay),
                        sanitize(error.getMessage()), clock.instant());
            }
        }
        return delivered;
    }

    private boolean deliverOne(MatrixOutboundMessage message, MatrixTransport transport) {
        return transactions.execute(status -> {
            if (!isBindingActiveForUpdate(message)) {
                outbound.suppress(message.id(), "Matrix room binding is missing, revoked, or mismatched",
                        clock.instant());
                return false;
            }
            transport.send(message);
            outbound.markSent(message.id(), clock.instant());
            return true;
        });
    }

    private boolean isBindingActiveForUpdate(MatrixOutboundMessage message) {
        if (message.tenantId() == null || message.tenantId().isBlank() || message.roomBindingId() == null) return false;
        return roomBindings.findByIdForUpdate(message.tenantId(), message.roomBindingId())
                .filter(binding -> binding.status() == MatrixRoomBindingStatus.ACTIVE)
                .filter(binding -> binding.roomId().equals(message.roomId())).isPresent();
    }

    @FunctionalInterface
    public interface MatrixTransport {
        void send(MatrixOutboundMessage message);
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) return "matrix delivery failed";
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}

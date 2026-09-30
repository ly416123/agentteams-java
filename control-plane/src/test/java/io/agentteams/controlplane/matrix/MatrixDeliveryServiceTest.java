package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class MatrixDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");

    @Test
    void retriesFailedDeliveryWithoutRollingBackTheProjectedFact() {
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID outboxId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        Fixture fixture = fixture(outbound, outboxId);
        MatrixOutboundMessage message = fixture.message();
        when(outbound.claimDue(NOW, 1)).thenReturn(List.of(message));

        MatrixDeliveryService service = fixture.service();

        int delivered = service.deliver(1, outboundMessage -> {
            throw new IllegalStateException("temporary Matrix outage");
        });

        assertThat(delivered).isZero();
        verify(outbound).retry(outboxId, NOW.plusSeconds(5), "temporary Matrix outage", NOW);
        verify(outbound, never()).markSent(eq(outboxId), eq(NOW));
    }

    @Test
    void usesRetryAfterForRetryableHomeserverFailures() {
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID id = UUID.randomUUID();
        Fixture fixture = fixture(outbound, id);
        MatrixOutboundMessage message = fixture.message();
        when(outbound.claimDue(NOW, 1)).thenReturn(List.of(message));
        MatrixDeliveryService service = fixture.service();

        service.deliver(1, ignored -> {
            throw new MatrixDeliveryException("rate limited", true, Duration.ofSeconds(17));
        });

        verify(outbound).retry(id, NOW.plusSeconds(17), "rate limited", NOW);
        verify(outbound, never()).suppress(id, "rate limited", NOW);
    }

    @Test
    void suppressesPermanentHomeserverRejections() {
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID id = UUID.randomUUID();
        Fixture fixture = fixture(outbound, id);
        MatrixOutboundMessage message = fixture.message();
        when(outbound.claimDue(NOW, 1)).thenReturn(List.of(message));
        MatrixDeliveryService service = fixture.service();

        service.deliver(1, ignored -> {
            throw new MatrixDeliveryException("rejected", false, null);
        });

        verify(outbound).claimDue(NOW, 1);
        verify(outbound).suppress(id, "rejected", NOW);
        verifyNoMoreInteractions(outbound);
    }

    @Test
    void suppressesQueuedMessagesWhenRoomBindingHasBeenRevoked() {
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        MatrixRoomBindingRepository bindings = mock(MatrixRoomBindingRepository.class);
        UUID id = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        MatrixOutboundMessage message = new MatrixOutboundMessage(id, "!room:example.org", "TaskPaused",
                "summary", "IN_FLIGHT", 1, NOW, null, "tenant-a", bindingId);
        when(outbound.claimDue(NOW, 1)).thenReturn(List.of(message));
        when(bindings.findByIdForUpdate("tenant-a", bindingId)).thenReturn(java.util.Optional.empty());
        MatrixDeliveryService service = new MatrixDeliveryService(outbound, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5), bindings, inlineTransactions());

        int delivered = service.deliver(1, ignored -> { throw new AssertionError("must not send revoked room"); });

        assertThat(delivered).isZero();
        verify(outbound).suppress(id, "Matrix room binding is missing, revoked, or mismatched", NOW);
    }

    private static Fixture fixture(MatrixOutboundRepository outbound, UUID id) {
        UUID bindingId = UUID.randomUUID();
        String tenantId = "tenant-a";
        MatrixRoomBindingRepository bindings = mock(MatrixRoomBindingRepository.class);
        when(bindings.findByIdForUpdate(tenantId, bindingId))
                .thenReturn(java.util.Optional.of(activeBinding(bindingId, tenantId)));
        MatrixOutboundMessage message = new MatrixOutboundMessage(id, "!room:example.org", "TaskPaused",
                "summary", "IN_FLIGHT", 1, NOW, null, tenantId, bindingId);
        MatrixDeliveryService service = new MatrixDeliveryService(outbound, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5), bindings, inlineTransactions());
        return new Fixture(service, message);
    }

    private static MatrixRoomBinding activeBinding(UUID id, String tenantId) {
        UUID projectId = UUID.randomUUID();
        return new MatrixRoomBinding(id, "org-a", tenantId, "!room:example.org", MatrixRoomContextType.PROJECT,
                projectId, projectId, null, MatrixRoomBindingStatus.ACTIVE, "admin", NOW, NOW, 1);
    }

    private static TransactionOperations inlineTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private record Fixture(MatrixDeliveryService service, MatrixOutboundMessage message) { }
}

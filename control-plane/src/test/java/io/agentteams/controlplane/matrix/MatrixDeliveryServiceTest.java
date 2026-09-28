package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");

    @Test
    void retriesFailedDeliveryWithoutRollingBackTheProjectedFact() {
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID outboxId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        MatrixOutboundMessage message = new MatrixOutboundMessage(outboxId, "!room:example.org", "TaskPaused",
                "TaskPaused TASK 00000000-0000-0000-0000-000000000004 v3", "IN_FLIGHT", 1,
                NOW, null);
        when(outbound.claimDue(NOW, 1)).thenReturn(List.of(message));

        MatrixDeliveryService service = new MatrixDeliveryService(outbound, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5));

        int delivered = service.deliver(1, (roomId, eventType, body) -> {
            throw new IllegalStateException("temporary Matrix outage");
        });

        assertThat(delivered).isZero();
        verify(outbound).retry(outboxId, NOW.plusSeconds(5), "temporary Matrix outage", NOW);
        verify(outbound, never()).markSent(eq(outboxId), eq(NOW));
    }
}

package io.agentteams.controlplane.quality;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactQualityEventDeliveryJobTest {
    @Test
    void failedDeliveryIsScheduledForBoundedRetryWithoutLeakingFailureText() {
        ArtifactQualityEventOutboxRepository outbox = mock(ArtifactQualityEventOutboxRepository.class);
        ArtifactQualityLedgerService ledger = mock(ArtifactQualityLedgerService.class);
        Instant now = Instant.parse("2026-10-15T02:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ArtifactQualityEvent event = mock(ArtifactQualityEvent.class);
        UUID eventId = UUID.randomUUID();
        when(event.id()).thenReturn(eventId);
        var pending = new ArtifactQualityEventOutboxRepository.PendingEvent(event, 2);
        when(outbox.claimDue(now, Duration.ofSeconds(30), 50)).thenReturn(List.of(pending));
        doThrow(new IllegalStateException("raw database details must not be persisted"))
                .when(ledger).deliverQueued(pending);

        new ArtifactQualityEventDeliveryJob(outbox, ledger, clock).deliverDue();

        verify(outbox).retry(eventId, now.plusSeconds(2), "IllegalStateException");
        verify(ledger).captureOutboxRetry(event);
    }
}

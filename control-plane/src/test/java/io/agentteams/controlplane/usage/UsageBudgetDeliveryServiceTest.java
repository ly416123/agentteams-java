package io.agentteams.controlplane.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageBudgetDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void failedNotificationIsRetriedAndThenMarkedSent() {
        UsageBudgetRepository events = mock(UsageBudgetRepository.class);
        UsageBudgetNotificationPort notifications = mock(UsageBudgetNotificationPort.class);
        UsageBudgetEvent event = event(UsageBudgetEvent.Status.FAILED, 1, NOW.minusSeconds(1));
        when(events.findPending(100)).thenReturn(List.of());
        when(events.findDue(NOW, 100)).thenReturn(List.of(event));
        when(events.claim(event, NOW)).thenReturn(java.util.Optional.of(event.retryClaimed(NOW)));
        when(notifications.notify(any())).thenReturn(new UsageBudgetNotificationPort.NotificationResult("test", true));

        UsageBudgetDeliveryService service = new UsageBudgetDeliveryService(events, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1));

        UsageBudgetDeliveryService.DeliveryResult result = service.deliverDue(NOW);

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(events).markSent(event.id(), NOW);
    }

    @Test
    void failedNotificationKeepsRetryState() {
        UsageBudgetRepository events = mock(UsageBudgetRepository.class);
        UsageBudgetNotificationPort notifications = mock(UsageBudgetNotificationPort.class);
        UsageBudgetEvent event = event(UsageBudgetEvent.Status.PENDING, 1, NOW);
        when(events.findPending(100)).thenReturn(List.of(event));
        when(events.findDue(NOW, 100)).thenReturn(List.of());
        when(notifications.notify(any())).thenThrow(new IllegalStateException("receiver unavailable"));

        UsageBudgetDeliveryService service = new UsageBudgetDeliveryService(events, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1));

        UsageBudgetDeliveryService.DeliveryResult result = service.deliverDue(NOW);

        assertThat(result.delivered()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(events).markFailed(event.id(), NOW.plus(Duration.ofMinutes(1)), "receiver unavailable", NOW);
    }

    private static UsageBudgetEvent event(UsageBudgetEvent.Status status, int attempts, Instant updatedAt) {
        return new UsageBudgetEvent(UUID.randomUUID(), "fingerprint", UUID.randomUUID(), "tenant-a", "project-a",
                "USD", NOW.minus(Duration.ofHours(1)), NOW, new BigDecimal("12"), new BigDecimal("24"),
                UsageBudgetEvaluation.Status.HARD_LIMIT, status, attempts,
                status == UsageBudgetEvent.Status.FAILED ? NOW : null, null, null, NOW, updatedAt);
    }
}

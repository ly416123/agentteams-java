package io.agentteams.controlplane.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.outbox.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final WebhookScope SCOPE = new WebhookScope("org-1", "tenant-1", "project-1");

    @Test
    void enqueuesOnlyAllowedEventTypesAndDeduplicatesEvent() {
        WebhookRepository repository = mock(WebhookRepository.class);
        WebhookDeliveryService service = new WebhookDeliveryService(repository, mock(WebhookTransport.class));
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(), SCOPE,
                "https://example.test/hooks", "secret-ref", Set.of("task.completed"), true, 0, NOW, NOW);
        when(repository.listEnabled(SCOPE)).thenReturn(List.of(subscription), List.of());
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "task.completed", "task", UUID.randomUUID(),
                1, NOW, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        when(repository.enqueue(subscription, event, NOW)).thenReturn(true);
        assertThat(service.enqueue(SCOPE, event, NOW)).isTrue();
        assertThat(service.enqueue(SCOPE, event, NOW)).isFalse();
        verify(repository).enqueue(subscription, event, NOW);
    }

    @Test
    void rejectsUnsafeEndpointsAndSensitivePayloadsBeforeEnqueue() {
        WebhookRepository repository = mock(WebhookRepository.class);
        WebhookDeliveryService service = new WebhookDeliveryService(repository, mock(WebhookTransport.class));
        assertThatThrownBy(() -> service.create(new WebhookDeliveryService.CreateRequest(SCOPE,
                "http://127.0.0.1/hook", "secret-ref", Set.of("task.completed")), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");
        assertThatThrownBy(() -> service.create(new WebhookDeliveryService.CreateRequest(SCOPE,
                "https://example.test/hook", "secret-ref", Set.of("unknown.event")), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> service.enqueue(SCOPE,
                new EventEnvelope(UUID.randomUUID(), "task.completed", "task", UUID.randomUUID(), 1, NOW,
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("secret", "x")), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sensitive");
    }

    @Test
    void retriesTransientTransportFailureAndDeadLettersAfterLimit() {
        WebhookRepository repository = mock(WebhookRepository.class);
        WebhookTransport transport = mock(WebhookTransport.class);
        WebhookDeliveryService service = new WebhookDeliveryService(repository, transport, 3);
        WebhookDelivery delivery = new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://example.test/hook", "secret-ref", "{}", WebhookDelivery.Status.PENDING, 1,
                NOW, NOW, NOW, null);
        when(repository.findDue(NOW, 10)).thenReturn(List.of(delivery));
        org.mockito.Mockito.doThrow(new IllegalStateException("network down")).when(transport).send(any());

        assertThat(service.deliverDue(NOW, 10)).isEqualTo(new WebhookDeliveryService.DeliveryResult(0, 1, 0));
        verify(repository).markRetry(delivery, NOW.plusSeconds(2), "network down", NOW);
    }
}

package io.agentteams.controlplane.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.outbox.EventEnvelope;
import io.agentteams.controlplane.webhook.WebhookRepository;
import io.agentteams.controlplane.webhook.WebhookScope;
import io.agentteams.controlplane.webhook.WebhookSubscription;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookChannelAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void queuesMessageThroughDurableWebhookRepository() {
        WebhookRepository repository = mock(WebhookRepository.class);
        UUID bindingId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(bindingId,
                new WebhookScope("org-1", "tenant-1", "project-1"), "https://example.test/hook", "secret-ref",
                Set.of("task.completed"), true, 0, NOW, NOW);
        when(repository.findById(bindingId)).thenReturn(java.util.Optional.of(subscription));
        when(repository.enqueue(any(), any(EventEnvelope.class), any(Instant.class))).thenReturn(true);
        WebhookChannelAdapter adapter = new WebhookChannelAdapter(repository, java.time.Clock.fixed(NOW,
                java.time.ZoneOffset.UTC));

        ChannelReceipt receipt = adapter.send(new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1"));

        assertThat(receipt.status()).isEqualTo(ChannelReceiptStatus.QUEUED);
        assertThat(receipt.messageId()).isNotNull();
        verify(repository).enqueue(any(WebhookSubscription.class), any(EventEnvelope.class), any(Instant.class));
    }

    @Test
    void duplicateMessageIsReportedWithoutSecondSideEffect() {
        WebhookRepository repository = mock(WebhookRepository.class);
        UUID bindingId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(bindingId,
                new WebhookScope("org-1", "tenant-1", "project-1"), "https://example.test/hook", "secret-ref",
                Set.of("task.completed"), true, 0, NOW, NOW);
        when(repository.findById(bindingId)).thenReturn(java.util.Optional.of(subscription));
        when(repository.enqueue(any(), any(EventEnvelope.class), any(Instant.class))).thenReturn(false);
        WebhookChannelAdapter adapter = new WebhookChannelAdapter(repository, java.time.Clock.fixed(NOW,
                java.time.ZoneOffset.UTC));

        UUID messageId = UUID.randomUUID();
        ChannelReceipt receipt = adapter.send(new ChannelMessage(messageId, "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1"));

        assertThat(receipt).isEqualTo(new ChannelReceipt(messageId, bindingId.toString(), ChannelReceiptStatus.DUPLICATE,
                null));
    }

    @Test
    void rejectsCrossTenantAndDisabledBindings() {
        WebhookRepository repository = mock(WebhookRepository.class);
        UUID bindingId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(bindingId,
                new WebhookScope("org-1", "tenant-1", "project-1"), "https://example.test/hook", "secret-ref",
                Set.of("task.completed"), false, 0, NOW, NOW);
        when(repository.findById(bindingId)).thenReturn(java.util.Optional.of(subscription));
        WebhookChannelAdapter adapter = new WebhookChannelAdapter(repository,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        assertThatThrownBy(() -> adapter.send(new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-2", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1")))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.AUTH_REJECTED);
        assertThatThrownBy(() -> adapter.send(new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1")))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.PERMANENT_REJECTION);
    }

    @Test
    void healthNeverExposesBindingAcrossTenant() {
        WebhookRepository repository = mock(WebhookRepository.class);
        UUID bindingId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(bindingId,
                new WebhookScope("org-1", "tenant-1", "project-1"), "https://example.test/hook", "secret-ref",
                Set.of("task.completed"), true, 0, NOW, NOW);
        when(repository.findById(bindingId)).thenReturn(java.util.Optional.of(subscription));
        WebhookChannelAdapter adapter = new WebhookChannelAdapter(repository,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        ChannelHealth health = adapter.health(new ChannelBinding(ChannelType.WEBHOOK, bindingId.toString(), "org-1",
                "tenant-2", "project-1"));

        assertThat(health.status()).isEqualTo(ChannelHealthStatus.UNAVAILABLE);
        assertThat(health.errorCategory()).isEqualTo(ChannelErrorCategory.AUTH_REJECTED);
    }
}

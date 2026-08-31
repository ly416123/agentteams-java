package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.webhook.WebhookDeliveryService;
import io.agentteams.controlplane.webhook.WebhookScope;
import io.agentteams.controlplane.webhook.WebhookSubscription;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    @AfterEach
    void clearPrincipal() {
        io.agentteams.controlplane.security.PrincipalContext.clear();
    }

    @Test
    void createsSubscriptionWithRequiredIdempotencyKeyAndDoesNotReturnSecretRef() {
        WebhookDeliveryService service = mock(WebhookDeliveryService.class);
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(),
                new WebhookScope("org-1", "tenant-1", "project-1"), "https://hooks.example.com/events",
                "secret-ref", Set.of("task.completed"), true, 0, NOW, NOW);
        when(service.create(any(WebhookDeliveryService.CreateRequest.class), any(Clock.class))).thenReturn(subscription);
        WebhookController controller = new WebhookController(service, Clock.fixed(NOW, ZoneOffset.UTC));

        WebhookController.CreateWebhookRequest request = new WebhookController.CreateWebhookRequest(
                "org-1", "tenant-1", "project-1", subscription.endpoint(), subscription.secretRef(),
                subscription.eventTypes());
        var response = controller.create("key-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().endpoint()).isEqualTo(subscription.endpoint());
        verify(service).create(any(WebhookDeliveryService.CreateRequest.class), any(Clock.class));
    }

    @Test
    void listsOnlySafeSubscriptionProjection() {
        WebhookDeliveryService service = mock(WebhookDeliveryService.class);
        WebhookScope scope = new WebhookScope("org-1", "tenant-1", "project-1");
        when(service.list(scope)).thenReturn(List.of(new WebhookSubscription(UUID.randomUUID(), scope,
                "https://hooks.example.com/events", "secret-ref", Set.of("task.result"), true, 0, NOW, NOW)));
        WebhookController controller = new WebhookController(service, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(controller.list("org-1", "tenant-1", "project-1")).hasSize(1);
        verify(service).list(scope);
    }
}

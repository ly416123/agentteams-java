package io.agentteams.controlplane.api;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.webhook.WebhookScope;
import io.agentteams.controlplane.webhook.WebhookSubscription;
import io.agentteams.controlplane.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped Webhook subscription management without exposing secret material. */
@RestController
@RequestMapping("/api/v1/webhooks")
public final class WebhookController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final WebhookDeliveryService service;
    private final Clock clock;

    public WebhookController(WebhookDeliveryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<WebhookResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateWebhookRequest request) {
        requireKey(idempotencyKey);
        if (request == null) throw new IllegalArgumentException("request body is required");
        requireCallerScope(request.tenantId(), request.projectId());
        WebhookSubscription created = service.create(request.toServiceRequest(), clock);
        return ResponseEntity.status(201).body(WebhookResponse.from(created));
    }

    @GetMapping
    public List<WebhookResponse> list(@RequestParam String organizationId, @RequestParam String tenantId,
            @RequestParam(required = false) String projectId) {
        requireCallerScope(tenantId, projectId);
        return serviceSubscriptions(new WebhookScope(organizationId, tenantId, projectId));
    }

    private List<WebhookResponse> serviceSubscriptions(WebhookScope scope) {
        return service.list(scope).stream().map(WebhookResponse::from).toList();
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
        }
    }

    private static void requireCallerScope(String tenantId, String projectId) {
        PrincipalContext.current().ifPresent(principal -> {
            if (!principal.scope().tenant().equals(tenantId)
                    || (projectId != null && !principal.scope().project().equals(projectId))) {
                throw new AuthorizationException("Webhook is outside the caller scope");
            }
        });
    }

    public record CreateWebhookRequest(String organizationId, String tenantId, String projectId,
            String endpoint, String secretRef, Set<String> eventTypes) {
        WebhookDeliveryService.CreateRequest toServiceRequest() {
            return new WebhookDeliveryService.CreateRequest(
                    new WebhookScope(organizationId, tenantId, projectId), endpoint, secretRef, eventTypes);
        }
    }

    public record WebhookResponse(UUID id, String organizationId, String tenantId, String projectId,
            String endpoint, Set<String> eventTypes, boolean enabled, long version,
            Instant createdAt, Instant updatedAt) {
        static WebhookResponse from(WebhookSubscription value) {
            return new WebhookResponse(value.id(), value.scope().organizationId(), value.scope().tenantId(),
                    value.scope().projectId(), value.endpoint(), value.eventTypes(), value.enabled(), value.version(),
                    value.createdAt(), value.updatedAt());
        }
    }
}

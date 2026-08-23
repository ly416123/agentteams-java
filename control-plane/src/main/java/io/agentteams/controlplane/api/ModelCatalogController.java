package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import io.agentteams.controlplane.service.ModelCatalogService;
import io.agentteams.controlplane.service.ModelProviderConnectionProbe;
import io.agentteams.controlplane.service.ValidationOnlyModelProviderConnectionProbe;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-providers")
public final class ModelCatalogController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final ModelCatalogService service;

    public ModelCatalogController(ModelCatalogService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ModelProviderResponse> createProvider(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateProviderRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        ModelProviderRecord provider = service.createProvider(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(ModelProviderResponse.from(provider));
    }

    @GetMapping
    public List<ModelProviderResponse> listProviders() {
        return service.listProviders().stream().map(ModelProviderResponse::from).toList();
    }

    @GetMapping("/{providerId}")
    public ModelProviderResponse getProvider(@PathVariable UUID providerId) {
        return ModelProviderResponse.from(service.getProvider(providerId));
    }

    @PatchMapping("/{providerId}")
    public ModelProviderResponse setProviderEnabled(@PathVariable UUID providerId,
            @RequestBody LifecycleRequest request) {
        requireRequest(request);
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return ModelProviderResponse.from(service.setProviderEnabled(providerId, request.enabled()));
    }

    @DeleteMapping("/{providerId}")
    public ResponseEntity<Void> deleteProvider(@PathVariable UUID providerId) {
        service.deleteProvider(providerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{providerId}/connection-test")
    public ConnectionTestResponse testProviderConnection(@PathVariable UUID providerId,
            @RequestBody(required = false) ConnectionTestRequest request) {
        long timeoutMs = request == null || request.timeoutMs() == null ? 5_000L : request.timeoutMs();
        if (timeoutMs <= 0 || timeoutMs > ValidationOnlyModelProviderConnectionProbe.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMs must be between 1 and 60000");
        }
        ModelProviderConnectionProbe.ProbeResult result = service.testProviderConnection(providerId,
                Duration.ofMillis(timeoutMs));
        return ConnectionTestResponse.from(providerId, timeoutMs, result);
    }

    @PostMapping("/{providerId}/models")
    public ResponseEntity<ModelResponse> createModel(
            @PathVariable UUID providerId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateModelRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        ModelRecord model = service.createModel(providerId, idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(ModelResponse.from(model));
    }

    @GetMapping("/{providerId}/models")
    public List<ModelResponse> listModels(@PathVariable UUID providerId) {
        return service.listModels(providerId).stream().map(ModelResponse::from).toList();
    }

    @GetMapping("/models/{modelId}")
    public ModelResponse getModel(@PathVariable UUID modelId) {
        return ModelResponse.from(service.getModel(modelId));
    }

    @PatchMapping("/models/{modelId}")
    public ModelResponse setModelEnabled(@PathVariable UUID modelId, @RequestBody LifecycleRequest request) {
        requireRequest(request);
        if (request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return ModelResponse.from(service.setModelEnabled(modelId, request.enabled()));
    }

    @DeleteMapping("/models/{modelId}")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID modelId) {
        service.deleteModel(modelId);
        return ResponseEntity.noContent().build();
    }

    public record LifecycleRequest(Boolean enabled) { }

    public record ConnectionTestRequest(Long timeoutMs) { }

    public record ConnectionTestResponse(UUID providerId, long timeoutMs,
            ModelProviderConnectionProbe.ProbeResult.Status status, String classification,
            boolean networkCallAttempted, List<ModelProviderConnectionProbe.ProbeResult.Check> checks) {

        static ConnectionTestResponse from(UUID providerId, long timeoutMs,
                ModelProviderConnectionProbe.ProbeResult result) {
            return new ConnectionTestResponse(providerId, timeoutMs, result.status(), result.classification(),
                    result.networkCallAttempted(), result.checks());
        }
    }

    public record CreateProviderRequest(String name, String providerType, String endpoint,
            String credentialRef, JsonNode settings, Boolean enabled) {

        ModelCatalogService.ProviderInput toServiceInput() {
            return new ModelCatalogService.ProviderInput(name, providerType, endpoint, credentialRef,
                    json(settings), enabled == null || enabled);
        }
    }

    public record CreateModelRequest(String name, String modelId, JsonNode capabilities, Boolean enabled) {

        ModelCatalogService.ModelInput toServiceInput() {
            return new ModelCatalogService.ModelInput(name, modelId, json(capabilities), enabled == null || enabled);
        }
    }

    public record ModelProviderResponse(UUID id, String name, String providerType, String endpoint,
            boolean credentialConfigured, boolean enabled, Instant createdAt, Instant updatedAt, long version) {

        static ModelProviderResponse from(ModelProviderRecord provider) {
            return new ModelProviderResponse(provider.id(), provider.name(), provider.providerType(),
                    provider.endpoint(), provider.credentialRef() != null && !provider.credentialRef().isBlank(),
                    provider.enabled(), provider.createdAt(), provider.updatedAt(), provider.version());
        }
    }

    public record ModelResponse(UUID id, UUID providerId, String name, String modelId,
            String capabilities, boolean enabled, Instant createdAt, Instant updatedAt, long version) {

        static ModelResponse from(ModelRecord model) {
            return new ModelResponse(model.id(), model.providerId(), model.name(), model.modelId(),
                    model.capabilitiesJson(), model.enabled(), model.createdAt(), model.updatedAt(), model.version());
        }
    }

    private static String json(JsonNode value) {
        if (value == null || value.isNull()) {
            return "{}";
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("JSON object is required");
        }
        return value.toString();
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}

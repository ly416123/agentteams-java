package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class ModelCatalogService {

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;

    @Autowired
    public ModelCatalogService(FoundationPersistenceService persistence, IdempotencyService idempotency) {
        this(persistence, idempotency, Clock.systemUTC());
    }

    ModelCatalogService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ModelProviderRecord createProvider(String idempotencyKey, ProviderInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String name = required(input.name(), "name");
        String providerType = required(input.providerType(), "providerType");
        String endpoint = endpoint(input.endpoint());
        String credentialRef = optional(input.credentialRef());
        String settings = jsonObjectOrDefault(input.settingsJson());
        Instant now = clock.instant();
        ModelProviderRecord provider = new ModelProviderRecord(UUID.randomUUID(), name, providerType, endpoint,
                credentialRef, settings, input.enabled(), now, now, 0);
        return persistence.createModelProvider(provider, key,
                idempotency.requestHash(name, providerType, endpoint, credentialRef, settings,
                        Boolean.toString(input.enabled())));
    }

    public List<ModelProviderRecord> listProviders() {
        return persistence.findModelProviders();
    }

    public ModelProviderRecord getProvider(UUID id) {
        return persistence.findModelProvider(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new ResourceNotFoundException("model provider", id));
    }

    public ModelRecord createModel(UUID providerId, String idempotencyKey, ModelInput input) {
        Objects.requireNonNull(input, "input");
        UUID provider = Objects.requireNonNull(providerId, "providerId");
        String key = idempotency.requireKey(idempotencyKey);
        String name = required(input.name(), "name");
        String modelId = required(input.modelId(), "modelId");
        String capabilities = jsonObjectOrDefault(input.capabilitiesJson());
        Instant now = clock.instant();
        ModelRecord model = new ModelRecord(UUID.randomUUID(), provider, name, modelId, capabilities,
                input.enabled(), now, now, 0);
        return persistence.createModel(model, key,
                idempotency.requestHash(provider.toString(), name, modelId, capabilities,
                        Boolean.toString(input.enabled())));
    }

    public List<ModelRecord> listModels(UUID providerId) {
        UUID provider = Objects.requireNonNull(providerId, "providerId");
        getProvider(provider);
        return persistence.findModelsByProvider(provider);
    }

    public ModelRecord getModel(UUID id) {
        return persistence.findModel(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new ResourceNotFoundException("model", id));
    }

    public record ProviderInput(String name, String providerType, String endpoint, String credentialRef,
            String settingsJson, boolean enabled) {
    }

    public record ModelInput(String name, String modelId, String capabilitiesJson, boolean enabled) {
    }

    private static String endpoint(String value) {
        String endpoint = required(value, "endpoint");
        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("endpoint must be an absolute URI");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint must be an absolute URI", error);
        }
        return endpoint;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String jsonObjectOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("JSON object is required");
        }
        return trimmed;
    }
}

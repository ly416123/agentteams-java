package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class AgentService {

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency) {
        this(persistence, idempotency, Clock.systemUTC());
    }

    AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AgentRecord create(String idempotencyKey, AgentInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String name = required(input.name(), "name");
        String runtime = required(input.runtime(), "runtime");
        String capabilities = jsonObjectOrDefault(input.capabilitiesJson());
        String metadata = jsonObjectOrDefault(input.metadataJson());
        Instant now = clock.instant();
        AgentRecord agent = new AgentRecord(UUID.randomUUID(), name, AgentPhase.PROVISIONING, runtime,
                capabilities, metadata, now, now, 0);
        String requestHash = idempotency.requestHash(name, runtime, capabilities, metadata);
        return persistence.createAgent(agent, key, requestHash);
    }

    public AgentRecord get(UUID id) {
        Objects.requireNonNull(id, "id");
        return persistence.findAgent(id).orElseThrow(() -> new ResourceNotFoundException("agent", id));
    }

    public record AgentInput(String name, String runtime, String capabilitiesJson, String metadataJson) {
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
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

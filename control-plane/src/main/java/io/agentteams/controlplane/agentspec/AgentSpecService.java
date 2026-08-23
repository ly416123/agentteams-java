package io.agentteams.controlplane.agentspec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public final class AgentSpecService {

    private final AgentSpecRepository repository;
    private final Clock clock;

    public AgentSpecService(AgentSpecRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AgentSpecService(AgentSpecRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public AgentSpecRecord create(String idempotencyKey, Input input) {
        String key = required(idempotencyKey, "Idempotency-Key");
        Objects.requireNonNull(input, "input");
        String hash = hash(input);
        var existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
            }
            return get(existing.get().specId());
        }

        Instant now = clock.instant();
        AgentSpecRecord record = new AgentSpecRecord(UUID.randomUUID(), required(input.name(), "name"),
                required(input.runtime(), "runtime"), required(input.modelProvider(), "modelProvider"),
                required(input.modelName(), "modelName"), optional(input.teamRef()),
                normalizeState(input.desiredState()), "DRAFT", objectJson(input.specJson()), now, now, 1);
        if (!repository.insertIdempotency(new AgentSpecRepository.IdempotencyRecord(key, hash, record.id(), now))) {
            var winner = repository.findIdempotency(key).orElseThrow();
            if (!winner.requestHash().equals(hash)) {
                throw new IllegalArgumentException("Idempotency-Key was already used with a different request");
            }
            return get(winner.specId());
        }
        repository.insert(record);
        return record;
    }

    public List<AgentSpecRecord> list() {
        return repository.findAll();
    }

    public AgentSpecRecord get(UUID id) {
        return repository.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("agent spec does not exist: " + id));
    }

    public record Input(String name, String runtime, String modelProvider, String modelName,
            String teamRef, String desiredState, String specJson) { }

    private static String normalizeState(String value) {
        String state = value == null || value.isBlank() ? "RUNNING" : value.trim().toUpperCase();
        if (!state.equals("RUNNING") && !state.equals("STOPPED")) {
            throw new IllegalArgumentException("desiredState must be RUNNING or STOPPED");
        }
        return state;
    }

    private static String objectJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        String json = value.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("spec must be a JSON object");
        }
        return json;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String hash(Input input) {
        String value = String.join("\u0000", Objects.toString(input.name(), ""),
                Objects.toString(input.runtime(), ""), Objects.toString(input.modelProvider(), ""),
                Objects.toString(input.modelName(), ""), Objects.toString(input.teamRef(), ""),
                Objects.toString(input.desiredState(), ""), Objects.toString(input.specJson(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}

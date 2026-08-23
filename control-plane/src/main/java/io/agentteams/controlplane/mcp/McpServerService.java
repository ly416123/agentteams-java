package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class McpServerService {

    private static final String CREATE_OPERATION = "CREATE_MCP_SERVER";

    private final McpServerRepository repository;
    private final Clock clock;

    public McpServerService(McpServerRepository repository) {
        this(repository, Clock.systemUTC());
    }

    McpServerService(McpServerRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public McpServerRecord create(String idempotencyKey, CreateInput input) {
        String key = requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(input, "input");
        NormalizedInput normalized = normalize(input);
        String requestHash = requestHash(normalized);

        var existing = repository.findIdempotency(key);
        if (existing.isPresent()) {
            return existingResource(key, requestHash, existing.get());
        }

        Instant now = clock.instant();
        if (repository.insertIdempotency(key, requestHash, now) == 0) {
            return existingResource(key, requestHash, repository.findIdempotency(key).orElseThrow());
        }

        McpServerRecord server = new McpServerRecord(UUID.randomUUID(), normalized.name(), normalized.transport(),
                normalized.endpoint(), normalized.credentialRef(), normalized.enabled(), normalized.healthStatus(),
                normalized.lastCheckedAt(), now, now, 0);
        repository.insert(server);
        repository.bindIdempotency(key, server.id());
        return server;
    }

    public List<McpServerRecord> list() {
        return repository.findAll();
    }

    public McpServerRecord get(UUID id) {
        return repository.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new ResourceNotFoundException("MCP server", id));
    }

    @Transactional
    public McpServerRecord update(UUID id, UpdateInput input) {
        Objects.requireNonNull(input, "input");
        McpServerRecord current = get(id);
        NormalizedInput normalized = normalize(input);
        Instant now = clock.instant();
        McpServerRecord updated = new McpServerRecord(current.id(), normalized.name(), normalized.transport(),
                normalized.endpoint(), normalized.credentialRef(), normalized.enabled(), normalized.healthStatus(),
                normalized.lastCheckedAt(), current.createdAt(), now, current.version() + 1);
        if (repository.update(updated, current.version()) != 1) {
            throw new OptimisticLockFailure("MCP_SERVER", id, current.version(), -1);
        }
        return updated;
    }

    @Transactional
    public McpServerRecord updateHealth(UUID id, HealthInput input) {
        Objects.requireNonNull(input, "input");
        McpServerRecord current = get(id);
        McpHealthStatus status = McpHealthStatus.parse(input.healthStatus());
        Instant checkedAt = input.lastCheckedAt() == null ? clock.instant() : input.lastCheckedAt();
        Instant now = clock.instant();
        if (repository.updateHealth(id, status, checkedAt, now, current.version()) != 1) {
            throw new OptimisticLockFailure("MCP_SERVER", id, current.version(), -1);
        }
        return new McpServerRecord(current.id(), current.name(), current.transport(), current.endpoint(),
                current.credentialRef(), current.enabled(), status, checkedAt, current.createdAt(), now,
                current.version() + 1);
    }

    @Transactional
    public void delete(UUID id) {
        if (repository.delete(Objects.requireNonNull(id, "id")) != 1) {
            throw new ResourceNotFoundException("MCP server", id);
        }
    }

    public record CreateInput(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt) {
    }

    public record UpdateInput(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt) {
    }

    public record HealthInput(String healthStatus, Instant lastCheckedAt) {
    }

    private McpServerRecord existingResource(String key, String requestHash,
            McpServerRepository.McpIdempotencyRecord existing) {
        if (!MessageDigest.isEqual(requestHash.getBytes(StandardCharsets.UTF_8),
                existing.requestHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IdempotencyConflictException(key, CREATE_OPERATION);
        }
        if (existing.serverId() == null) {
            throw new IllegalStateException("MCP server idempotency record is incomplete");
        }
        return get(existing.serverId());
    }

    private static NormalizedInput normalize(CreateInput input) {
        return normalize(input.name(), input.transport(), input.endpoint(), input.credentialRef(), input.enabled(),
                input.healthStatus(), input.lastCheckedAt());
    }

    private static NormalizedInput normalize(UpdateInput input) {
        return normalize(input.name(), input.transport(), input.endpoint(), input.credentialRef(), input.enabled(),
                input.healthStatus(), input.lastCheckedAt());
    }

    private static NormalizedInput normalize(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt) {
        String normalizedName = required(name, "name");
        if (normalizedName.length() > 255) {
            throw new IllegalArgumentException("name must be at most 255 characters");
        }
        McpTransport normalizedTransport = McpTransport.parse(transport);
        String normalizedEndpoint = absoluteHttpUri(endpoint);
        String normalizedCredentialRef = optional(credentialRef);
        if (normalizedCredentialRef != null && normalizedCredentialRef.length() > 500) {
            throw new IllegalArgumentException("credentialRef must be at most 500 characters");
        }
        McpHealthStatus normalizedHealthStatus = McpHealthStatus.parse(healthStatus);
        return new NormalizedInput(normalizedName, normalizedTransport, normalizedEndpoint,
                normalizedCredentialRef, enabled == null || enabled, normalizedHealthStatus, lastCheckedAt);
    }

    private static String absoluteHttpUri(String value) {
        String endpoint = required(value, "endpoint");
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI", error);
        }
        return endpoint;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String key = value.trim();
        if (key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
        return key;
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

    private static String requestHash(NormalizedInput input) {
        String payload = String.join("\u0000", input.name(), input.transport().name(), input.endpoint(),
                input.credentialRef() == null ? "" : input.credentialRef(), Boolean.toString(input.enabled()),
                input.healthStatus().name(), input.lastCheckedAt() == null ? "" : input.lastCheckedAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record NormalizedInput(String name, McpTransport transport, String endpoint, String credentialRef,
            boolean enabled, McpHealthStatus healthStatus, Instant lastCheckedAt) {
    }
}

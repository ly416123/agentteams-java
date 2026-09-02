package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.OutboundPolicy;
import io.agentteams.controlplane.security.OutboundPolicyValidator;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.security.CredentialReferenceValidator;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpServerService {

    private static final String CREATE_OPERATION = "CREATE_MCP_SERVER";

    private final McpServerRepository repository;
    private final Clock clock;
    private final AuditRecorder auditRecorder;
    private final OutboundPolicyValidator outboundPolicyValidator;
    private final ResourceScopeRepository resourceScopes;

    public McpServerService(McpServerRepository repository) {
        this(repository, Clock.systemUTC(), event -> { }, new OutboundPolicyValidator(), null);
    }

    @Autowired
    public McpServerService(McpServerRepository repository, AuditRecorder auditRecorder,
            org.springframework.beans.factory.ObjectProvider<ResourceScopeRepository> scopes) {
        this(repository, Clock.systemUTC(), auditRecorder, new OutboundPolicyValidator(), scopes.getIfAvailable());
    }

    McpServerService(McpServerRepository repository, Clock clock) {
        this(repository, clock, event -> { }, new OutboundPolicyValidator(), null);
    }

    McpServerService(McpServerRepository repository, Clock clock, AuditRecorder auditRecorder) {
        this(repository, clock, auditRecorder, new OutboundPolicyValidator(), null);
    }

    McpServerService(McpServerRepository repository, Clock clock, AuditRecorder auditRecorder,
            OutboundPolicyValidator outboundPolicyValidator) {
        this(repository, clock, auditRecorder, outboundPolicyValidator, null);
    }

    McpServerService(McpServerRepository repository, Clock clock, AuditRecorder auditRecorder,
            OutboundPolicyValidator outboundPolicyValidator, ResourceScopeRepository resourceScopes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.outboundPolicyValidator = Objects.requireNonNull(outboundPolicyValidator, "outboundPolicyValidator");
        this.resourceScopes = resourceScopes;
    }

    @Transactional
    public McpServerRecord create(String idempotencyKey, CreateInput input) {
        String actor = PrincipalContext.actorOr("api");
        UUID resourceId = null;
        try {
            String key = requireIdempotencyKey(idempotencyKey);
            Objects.requireNonNull(input, "input");
            NormalizedInput normalized = normalize(input);
            String requestHash = requestHash(normalized);

            var existing = repository.findIdempotency(key);
            if (existing.isPresent()) {
                McpServerRecord result = existingResource(key, requestHash, existing.get());
                record(actor, CREATE_OPERATION, result.id(), "SUCCESS");
                return result;
            }

            Instant now = clock.instant();
            if (repository.insertIdempotency(key, requestHash, now) == 0) {
                McpServerRecord result = existingResource(key, requestHash, repository.findIdempotency(key).orElseThrow());
                record(actor, CREATE_OPERATION, result.id(), "SUCCESS");
                return result;
            }

            McpServerRecord server = new McpServerRecord(UUID.randomUUID(), normalized.name(), normalized.transport(),
                    normalized.endpoint(), normalized.credentialRef(), normalized.enabled(), normalized.healthStatus(),
                    normalized.lastCheckedAt(), now, now, 0, normalized.outboundPolicy());
            resourceId = server.id();
            repository.insert(server);
            repository.bindIdempotency(key, server.id());
            bindIfAuthenticated(server.id());
            record(actor, CREATE_OPERATION, server.id(), "SUCCESS");
            return server;
        } catch (RuntimeException error) {
            record(actor, CREATE_OPERATION, resourceId, "FAILURE");
            throw error;
        }
    }

    public List<McpServerRecord> list() {
        return repository.findAll().stream().filter(server -> visible(server.id())).toList();
    }

    public McpServerRecord get(UUID id) {
        McpServerRecord server = repository.findById(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new ResourceNotFoundException("MCP server", id));
        if (resourceScopes != null) resourceScopes.requireVisible("MCP_SERVER", server.id());
        return server;
    }

    @Transactional
    public McpServerRecord update(UUID id, UpdateInput input) {
        return update(id, input, null);
    }

    @Transactional
    public McpServerRecord update(UUID id, UpdateInput input, Long expectedVersion) {
        String actor = PrincipalContext.actorOr("api");
        try {
            Objects.requireNonNull(input, "input");
            McpServerRecord current = get(id);
            if (expectedVersion != null && expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            if (expectedVersion != null && current.version() != expectedVersion) {
                throw new OptimisticLockFailure("MCP_SERVER", id, expectedVersion, current.version());
            }
            NormalizedInput normalized = normalize(input);
            Instant now = clock.instant();
            McpServerRecord updated = new McpServerRecord(current.id(), normalized.name(), normalized.transport(),
                    normalized.endpoint(), normalized.credentialRef(), normalized.enabled(), normalized.healthStatus(),
                    normalized.lastCheckedAt(), current.createdAt(), now, current.version() + 1,
                    normalized.outboundPolicy());
            if (repository.update(updated, current.version()) != 1) {
                throw new OptimisticLockFailure("MCP_SERVER", id, current.version(), -1);
            }
            record(actor, "UPDATE_MCP_SERVER", id, "SUCCESS");
            return updated;
        } catch (RuntimeException error) {
            record(actor, "UPDATE_MCP_SERVER", id, "FAILURE");
            throw error;
        }
    }

    @Transactional
    public McpServerRecord updateHealth(UUID id, HealthInput input) {
        String actor = PrincipalContext.actorOr("api");
        try {
            Objects.requireNonNull(input, "input");
            McpServerRecord current = get(id);
            McpHealthStatus status = McpHealthStatus.parse(input.healthStatus());
            Instant checkedAt = input.lastCheckedAt() == null ? clock.instant() : input.lastCheckedAt();
            Instant now = clock.instant();
            if (repository.updateHealth(id, status, checkedAt, now, current.version()) != 1) {
                throw new OptimisticLockFailure("MCP_SERVER", id, current.version(), -1);
            }
            McpServerRecord updated = new McpServerRecord(current.id(), current.name(), current.transport(), current.endpoint(),
                    current.credentialRef(), current.enabled(), status, checkedAt, current.createdAt(), now,
                    current.version() + 1);
            record(actor, "UPDATE_MCP_SERVER_HEALTH", id, "SUCCESS");
            return updated;
        } catch (RuntimeException error) {
            record(actor, "UPDATE_MCP_SERVER_HEALTH", id, "FAILURE");
            throw error;
        }
    }

    @Transactional
    public void delete(UUID id) {
            String actor = PrincipalContext.actorOr("api");
        try {
            UUID serverId = Objects.requireNonNull(id, "id");
            if (PrincipalContext.current().isPresent()) get(serverId);
            if (repository.delete(serverId) != 1) {
                throw new ResourceNotFoundException("MCP server", id);
            }
            record(actor, "DELETE_MCP_SERVER", serverId, "SUCCESS");
        } catch (RuntimeException error) {
            record(actor, "DELETE_MCP_SERVER", id, "FAILURE");
            throw error;
        }
    }

    public record CreateInput(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy) {
        public CreateInput(String name, String transport, String endpoint, String credentialRef,
                Boolean enabled, String healthStatus, Instant lastCheckedAt) {
            this(name, transport, endpoint, credentialRef, enabled, healthStatus, lastCheckedAt, null);
        }
    }

    public record UpdateInput(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy) {
        public UpdateInput(String name, String transport, String endpoint, String credentialRef,
                Boolean enabled, String healthStatus, Instant lastCheckedAt) {
            this(name, transport, endpoint, credentialRef, enabled, healthStatus, lastCheckedAt, null);
        }
    }

    public record HealthInput(String healthStatus, Instant lastCheckedAt) {
    }

    private void bindIfAuthenticated(UUID resourceId) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("MCP_SERVER", resourceId, principal, clock.instant()));
        }
    }

    private boolean visible(UUID resourceId) {
        return resourceScopes == null || resourceScopes.visible("MCP_SERVER", resourceId);
    }

    private void record(String actor, String action, UUID resourceId, String result) {
        try {
            auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, action, "mcp_server",
                    resourceId == null ? "unknown" : resourceId.toString(), Map.of("result", result), clock.instant()));
        } catch (RuntimeException ignored) {
            // Audit is best effort and must never change the MCP operation outcome.
        }
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

    private NormalizedInput normalize(CreateInput input) {
        return normalize(input.name(), input.transport(), input.endpoint(), input.credentialRef(), input.enabled(),
                input.healthStatus(), input.lastCheckedAt(), input.outboundPolicy());
    }

    private NormalizedInput normalize(UpdateInput input) {
        return normalize(input.name(), input.transport(), input.endpoint(), input.credentialRef(), input.enabled(),
                input.healthStatus(), input.lastCheckedAt(), input.outboundPolicy());
    }

    private NormalizedInput normalize(String name, String transport, String endpoint, String credentialRef,
            Boolean enabled, String healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy) {
        String normalizedName = required(name, "name");
        if (normalizedName.length() > 255) {
            throw new IllegalArgumentException("name must be at most 255 characters");
        }
        McpTransport normalizedTransport = McpTransport.parse(transport);
        OutboundPolicy normalizedPolicy = outboundPolicy == null
                ? OutboundPolicy.legacyCompatible() : outboundPolicy;
        String normalizedEndpoint = outboundPolicyValidator.validateEndpoint(required(endpoint, "endpoint"),
                normalizedPolicy).toString();
        String normalizedCredentialRef = CredentialReferenceValidator.normalize(credentialRef);
        McpHealthStatus normalizedHealthStatus = McpHealthStatus.parse(healthStatus);
        return new NormalizedInput(normalizedName, normalizedTransport, normalizedEndpoint,
                normalizedCredentialRef, enabled == null || enabled, normalizedHealthStatus, lastCheckedAt,
                normalizedPolicy);
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
            boolean enabled, McpHealthStatus healthStatus, Instant lastCheckedAt, OutboundPolicy outboundPolicy) {
    }
}

package io.agentteams.controlplane.mcp;

import io.agentteams.application.api.McpConnectivityMode;
import io.agentteams.controlplane.security.ExecutionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-aware MCP connection registry used as the seam for the JDBC implementation. */
public final class McpConnectionService {
    private final McpConnectionRepository repository;
    private final Map<UUID, McpConnection> connections = new LinkedHashMap<>();
    private final Map<String, McpConnection> idempotency = new LinkedHashMap<>();

    public McpConnectionService() {
        this(null);
    }

    @Autowired
    public McpConnectionService(McpConnectionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public synchronized McpConnectionView create(String idempotencyKey, CreateInput input,
            ExecutionContext context, Instant now) {
        String key = required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(now, "now");
        CreateInput normalized = normalize(input);
        validateOwnership(normalized, context);
        String requestHash = hash(normalized);
        if (repository != null) {
            Optional<McpConnection> existing = repository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                if (!existing.get().requestHash().equals(requestHash)) {
                    throw new IllegalArgumentException("idempotency key is already bound to a different request");
                }
                return view(existing.get());
            }
            McpConnection connection = new McpConnection(UUID.randomUUID(), normalized.name(), normalized.mode(),
                    normalized.organizationId(), normalized.tenantId(), normalized.endpointRef(),
                    normalized.credentialRef(), normalized.allowedTools(), Boolean.TRUE.equals(normalized.enabled()),
                    normalized.connectorId(), key, requestHash, now);
            if (!repository.insert(connection)) {
                McpConnection winner = repository.findByIdempotencyKey(key)
                        .orElseThrow(() -> new IllegalStateException("MCP idempotency record disappeared"));
                if (!winner.requestHash().equals(requestHash)) {
                    throw new IllegalArgumentException("idempotency key is already bound to a different request");
                }
                return view(winner);
            }
            return view(connection);
        }
        McpConnection existing = idempotency.get(key);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw new IllegalArgumentException("idempotency key is already bound to a different request");
            }
            return view(existing);
        }
        McpConnection connection = new McpConnection(UUID.randomUUID(), normalized.name(), normalized.mode(),
                normalized.organizationId(), normalized.tenantId(), normalized.endpointRef(),
                normalized.credentialRef(), normalized.allowedTools(), Boolean.TRUE.equals(normalized.enabled()),
                normalized.connectorId(), key, requestHash, now);
        connections.put(connection.id(), connection);
        idempotency.put(key, connection);
        return view(connection);
    }

    public synchronized Optional<McpConnectionView> get(UUID id, ExecutionContext context) {
        if (repository != null) return repository.find(Objects.requireNonNull(id, "id"), context).map(this::view);
        McpConnection connection = connections.get(Objects.requireNonNull(id, "id"));
        return connection != null && visible(connection, context) ? Optional.of(view(connection)) : Optional.empty();
    }

    public synchronized List<McpConnectionView> list(ExecutionContext context) {
        if (repository != null) return repository.find(context).stream().map(this::view).toList();
        return connections.values().stream().filter(connection -> visible(connection, context)).map(this::view).toList();
    }

    private static void validateOwnership(CreateInput input, ExecutionContext context) {
        if (input.mode() == McpConnectivityMode.PLATFORM_PUBLIC) {
            if (input.organizationId() != null || input.tenantId() != null || input.connectorId() != null) {
                throw new IllegalArgumentException("public MCP connection cannot have tenant or connector ownership");
            }
            return;
        }
        String organizationId = required(input.organizationId(), "organizationId");
        String tenantId = required(input.tenantId(), "tenantId");
        if (context == null || !organizationId.equals(context.organizationId()) || !tenantId.equals(context.tenantId())) {
            throw new IllegalArgumentException("MCP connection ownership does not match execution context");
        }
        if (input.mode() == McpConnectivityMode.CUSTOMER_CONNECTOR && optional(input.connectorId()) == null) {
            throw new IllegalArgumentException("connectorId is required for CUSTOMER_CONNECTOR");
        }
        if (input.mode() == McpConnectivityMode.CUSTOMER_CONNECTOR
                && !input.connectorId().startsWith(tenantId + "/")) {
            throw new IllegalArgumentException("connectorId is not owned by the requested tenant");
        }
    }

    private static CreateInput normalize(CreateInput input) {
        return new CreateInput(required(input.name(), "name"), input.mode(), optional(input.organizationId()),
                optional(input.tenantId()), required(input.endpointRef(), "endpointRef"),
                optional(input.credentialRef()), Set.copyOf(input.allowedTools()),
                input.enabled() == null ? Boolean.TRUE : input.enabled(), optional(input.connectorId()));
    }

    private static boolean visible(McpConnection connection, ExecutionContext context) {
        if (connection.mode() == McpConnectivityMode.PLATFORM_PUBLIC) return true;
        return context != null && connection.organizationId().equals(context.organizationId())
                && connection.tenantId().equals(context.tenantId());
    }

    private McpConnectionView view(McpConnection connection) {
        String digest = connection.credentialRef() == null ? null : sha256(connection.credentialRef());
        return new McpConnectionView(connection.id(), connection.name(), connection.mode(), connection.organizationId(),
                connection.tenantId(), connection.endpointRef(), connection.allowedTools(), connection.enabled(),
                connection.connectorId(), connection.credentialRef() != null, digest, connection.createdAt());
    }

    private static String hash(CreateInput input) {
        String canonical = String.join("\u0000", input.name(), input.mode().name(), nullToEmpty(input.organizationId()),
                nullToEmpty(input.tenantId()), input.endpointRef(), nullToEmpty(input.credentialRef()),
                new ArrayList<>(input.allowedTools()).stream().sorted().toList().toString(),
                String.valueOf(input.enabled()), nullToEmpty(input.connectorId()));
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CreateInput(String name, McpConnectivityMode mode, String organizationId, String tenantId,
            String endpointRef, String credentialRef, Set<String> allowedTools, Boolean enabled, String connectorId) {
        public CreateInput {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(allowedTools, "allowedTools");
            if (allowedTools.stream().anyMatch(tool -> tool == null || tool.isBlank())) {
                throw new IllegalArgumentException("allowedTools must contain non-blank names");
            }
        }

        public CreateInput(String name, McpConnectivityMode mode, String organizationId, String tenantId,
                String endpointRef, String credentialRef, Set<String> allowedTools, Boolean enabled) {
            this(name, mode, organizationId, tenantId, endpointRef, credentialRef, allowedTools, enabled, null);
        }
    }
}

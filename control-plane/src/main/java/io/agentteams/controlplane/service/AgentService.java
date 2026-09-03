package io.agentteams.controlplane.service;

import io.agentteams.controlplane.api.CursorPage;
import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.agent.WorkerType;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public final class AgentService {

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final ResourceScopeRepository resourceScopes;

    public AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency) {
        this(persistence, idempotency, Clock.systemUTC(), null);
    }

    @Autowired
    public AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            ObjectProvider<ResourceScopeRepository> scopes) {
        this(persistence, idempotency, Clock.systemUTC(), scopes.getIfAvailable());
    }

    AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock) {
        this(persistence, idempotency, clock, null);
    }

    AgentService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock,
            ResourceScopeRepository resourceScopes) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.resourceScopes = resourceScopes;
    }

    public AgentRecord create(String idempotencyKey, AgentInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String name = required(input.name(), "name");
        String runtime = required(input.runtime(), "runtime");
        WorkerType workerType = input.workerType() == null ? WorkerType.EXECUTOR : input.workerType();
        String capabilities = jsonObjectOrDefault(input.capabilitiesJson());
        String metadata = jsonObjectOrDefault(input.metadataJson());
        Instant now = clock.instant();
        AgentRecord agent = new AgentRecord(UUID.randomUUID(), name, workerType, AgentPhase.PROVISIONING, runtime,
                capabilities, metadata, now, now, 0);
        String requestHash = idempotency.requestHash(name, runtime, workerType.name(), capabilities, metadata);
        AgentRecord result = persistence.createAgent(agent, key, requestHash);
        bindIfAuthenticated(result.id());
        requireVisible(result.id());
        return result;
    }

    public AgentRecord get(UUID id) {
        Objects.requireNonNull(id, "id");
        AgentRecord agent = persistence.findAgent(id)
                .orElseThrow(() -> new ResourceNotFoundException("agent", id));
        requireVisible(agent.id());
        return agent;
    }

    public CursorPage<AgentRecord> list(CursorPageRequest request) {
        return list(request, null, null);
    }

    public CursorPage<AgentRecord> list(CursorPageRequest request, String status, String query) {
        Objects.requireNonNull(request, "request");
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        java.util.List<AgentRecord> rows = persistence.inTransaction(tx ->
                tx.agents().findPage(principal, request.position(), request.pageSize() + 1, request.direction(),
                        status, query));
        return CursorPage.fromRows(rows, request.pageSize(),
                agent -> new CursorPageRequest.Position(agent.updatedAt(), agent.id()), clock.instant());
    }

    /** Validates the Project route against the authenticated Project scope. */
    public void requireProjectScope(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new io.agentteams.controlplane.security.AuthorizationException(
                        "authentication required"));
        if (resourceScopes != null) {
            io.agentteams.controlplane.security.Principal canonical = resourceScopes.canonicalize(principal, projectId);
            if (canonical != null) {
                PrincipalContext.set(canonical);
                return;
            }
        }
        if (projectId.equals(principal.scope().project())) return;
        if (resourceScopes == null || !resourceScopes.matchesCallerProject(projectId)) {
            throw new io.agentteams.controlplane.security.AuthorizationException(
                    "resource is outside caller project");
        }
        io.agentteams.controlplane.security.Principal canonical = resourceScopes.canonicalize(principal, projectId);
        if (canonical != null) PrincipalContext.set(canonical);
    }

    public record AgentInput(String name, String runtime, WorkerType workerType, String capabilitiesJson,
            String metadataJson) {
        public AgentInput(String name, String runtime, String capabilitiesJson, String metadataJson) {
            this(name, runtime, WorkerType.EXECUTOR, capabilitiesJson, metadataJson);
        }
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

    private void bindIfAuthenticated(UUID resourceId) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("WORKER", resourceId, principal, clock.instant()));
        }
    }

    private void requireVisible(UUID resourceId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("WORKER", resourceId);
        }
    }
}

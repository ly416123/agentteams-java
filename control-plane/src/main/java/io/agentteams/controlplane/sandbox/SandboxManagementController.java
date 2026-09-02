package io.agentteams.controlplane.sandbox;

import io.agentteams.application.api.SandboxStatus;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskSandboxRecord;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scope-filtered Sandbox metadata for operations; provider secrets never leave the runtime boundary. */
@RestController
@RequestMapping("/api/v1/sandboxes")
public final class SandboxManagementController {
    private final FoundationPersistenceService persistence;
    private final ResourceScopeRepository scopes;
    private final ExecutionContextResolver contexts;

    public SandboxManagementController(FoundationPersistenceService persistence, ResourceScopeRepository scopes,
            ExecutionContextResolver contexts) {
        this.persistence = persistence;
        this.scopes = scopes;
        this.contexts = contexts;
    }

    @GetMapping
    public List<SandboxResponse> list(@RequestParam(defaultValue = "100") int limit) {
        PrincipalContext.executionContext(contexts).orElseThrow(() ->
                new IllegalStateException("authentication required"));
        return persistence.inTransaction(tx -> tx.taskSandboxes().findLatest(limit)).stream()
                .filter(record -> scopes.visible("TASK", record.taskId()))
                .map(SandboxResponse::from).toList();
    }

    public record SandboxResponse(UUID id, UUID taskId, UUID attemptId, String profile, SandboxStatus status,
            String endpointRef, Instant requestedAt, Instant expiresAt, Instant lastObservedAt,
            String failureCode, String redactedFailureMessage, long version) {
        static SandboxResponse from(TaskSandboxRecord value) {
            return new SandboxResponse(value.id(), value.taskId(), value.attemptId(), value.profile().name(),
                    value.status(), value.endpointRef(), value.requestedAt(), value.expiresAt(), value.lastObservedAt(),
                    value.failureCode(), value.redactedFailureMessage(), value.version());
        }
    }
}

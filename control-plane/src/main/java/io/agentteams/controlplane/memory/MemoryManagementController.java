package io.agentteams.controlplane.memory;

import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management boundary for metadata-only memory governance. */
@RestController
@RequestMapping("/api/v1/memory")
public final class MemoryManagementController {
    private final MemoryRepository memories;
    private final MemoryGovernanceService governance;
    private final ExecutionContextResolver contexts;

    public MemoryManagementController(MemoryRepository memories, MemoryGovernanceService governance,
            ExecutionContextResolver contexts) {
        this.memories = memories;
        this.governance = governance;
        this.contexts = contexts;
    }

    @GetMapping
    public List<MemoryMetadataResponse> list() {
        ExecutionContext context = context();
        return memories.find(context.organizationId(), context.tenantId(), context.projectId()).stream()
                .filter(memory -> MemoryScopeVisibility.visibleToList(memory, context))
                .map(MemoryMetadataResponse::from).toList();
    }

    @PostMapping("/{memoryId}/governance")
    public ResponseEntity<?> govern(@PathVariable UUID memoryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody GovernanceRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        Principal principal = PrincipalContext.current().orElseThrow(() ->
                new IllegalStateException("authentication required"));
        ExecutionContext context = contexts.resolve(principal);
        MemoryGovernanceActor actor = new MemoryGovernanceActor(principal.subject(),
                principal.permissions().contains("memory:govern"));
        String operation = request == null || request.operation() == null ? "" : request.operation().toUpperCase();
        String reason = request == null ? null : request.reason();
        return switch (operation) {
            case "CONFIRM" -> ResponseEntity.ok(MemoryMetadataResponse.from(
                    governance.confirm(context, memoryId, actor, reason, idempotencyKey)));
            case "REVOKE" -> ResponseEntity.ok(MemoryMetadataResponse.from(
                    governance.revoke(context, memoryId, actor, reason, idempotencyKey)));
            case "FREEZE" -> ResponseEntity.ok(MemoryMetadataResponse.from(
                    governance.freeze(context, memoryId, actor, reason, idempotencyKey)));
            case "DELETE" -> ResponseEntity.ok(MemoryMetadataResponse.from(
                    governance.delete(context, memoryId, actor, reason, idempotencyKey)));
            case "EXPORT" -> ResponseEntity.ok(governance.exportMetadata(context, memoryId, actor, reason,
                    idempotencyKey));
            default -> throw new IllegalArgumentException("unsupported memory governance operation");
        };
    }

    private ExecutionContext context() {
        return PrincipalContext.executionContext(contexts).orElseThrow(() ->
                new IllegalStateException("authentication required"));
    }

    public record GovernanceRequest(String operation, String reason) { }

    public record MemoryMetadataResponse(UUID id, MemoryPolicyView policy, String source, Instant expiresAt,
            Instant createdAt, Instant updatedAt, long version, MemoryRecord.GovernanceStatus governanceStatus) {
        static MemoryMetadataResponse from(MemoryRecord memory) {
            MemoryPolicyView policy = new MemoryPolicyView(memory.policy().scope(), memory.policy().projectId(),
                    memory.policy().teamId(), memory.policy().taskId(), memory.policy().subjectId(),
                    memory.policy().sensitivity(), memory.policy().consent());
            return new MemoryMetadataResponse(memory.id(), policy, memory.source(), memory.expiresAt(),
                    memory.createdAt(), memory.updatedAt(), memory.version(), memory.governanceStatus());
        }
    }

    public record MemoryPolicyView(io.agentteams.application.api.MemoryPolicy.Scope scope, String projectId,
            String teamId, String taskId, String subjectId,
            io.agentteams.application.api.MemoryPolicy.Sensitivity sensitivity,
            io.agentteams.application.api.MemoryPolicy.Consent consent) { }
}

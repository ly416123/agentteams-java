package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies tenant-scoped, auditable memory governance without exposing memory content. */
@Service
public final class MemoryGovernanceService {
    private final MemoryGovernanceRepository repository;
    private final Clock clock;

    public MemoryGovernanceService(MemoryGovernanceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MemoryGovernanceService(MemoryGovernanceRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public MemoryRecord confirm(ExecutionContext context, UUID memoryId, MemoryGovernanceActor actor, String reason,
            String idempotencyKey) {
        return change(context, memoryId, actor, reason, idempotencyKey, "CONFIRM",
                memory -> memory.withPolicy(withConsent(memory.policy(), MemoryPolicy.Consent.CONFIRMED), clock.instant()));
    }

    @Transactional
    public MemoryRecord revoke(ExecutionContext context, UUID memoryId, MemoryGovernanceActor actor, String reason,
            String idempotencyKey) {
        return change(context, memoryId, actor, reason, idempotencyKey, "REVOKE",
                memory -> memory.withPolicy(withConsent(memory.policy(), MemoryPolicy.Consent.REVOKED), clock.instant()));
    }

    @Transactional
    public MemoryRecord freeze(ExecutionContext context, UUID memoryId, MemoryGovernanceActor actor, String reason,
            String idempotencyKey) {
        requireAdministrator(actor);
        return change(context, memoryId, actor, reason, idempotencyKey, "FREEZE",
                memory -> memory.withGovernanceStatus(MemoryRecord.GovernanceStatus.FROZEN, clock.instant()));
    }

    @Transactional
    public MemoryRecord delete(ExecutionContext context, UUID memoryId, MemoryGovernanceActor actor, String reason,
            String idempotencyKey) {
        requireAdministrator(actor);
        return change(context, memoryId, actor, reason, idempotencyKey, "DELETE",
                memory -> memory.withGovernanceStatus(MemoryRecord.GovernanceStatus.DELETED, clock.instant()));
    }

    @Transactional
    public MemoryGovernanceExport exportMetadata(ExecutionContext context, UUID memoryId,
            MemoryGovernanceActor actor, String reason, String idempotencyKey) {
        requireAdministrator(actor);
        MemoryRecord memory = load(context, memoryId);
        record(context, memory, actor, reason, idempotencyKey, "EXPORT");
        return MemoryGovernanceExport.metadata(memory);
    }

    private MemoryRecord change(ExecutionContext context, UUID memoryId, MemoryGovernanceActor actor, String reason,
            String idempotencyKey, String operation, java.util.function.UnaryOperator<MemoryRecord> update) {
        MemoryRecord memory = load(context, memoryId);
        requirePermitted(memory, actor);
        if (!record(context, memory, actor, reason, idempotencyKey, operation)) return memory;
        return repository.save(update.apply(memory));
    }

    private MemoryRecord load(ExecutionContext context, UUID memoryId) {
        Objects.requireNonNull(context, "context");
        MemoryRecord memory = repository.findById(Objects.requireNonNull(memoryId, "memoryId"),
                context.organizationId(), context.tenantId()).orElseThrow(() ->
                new IllegalArgumentException("memory is outside the execution context"));
        if (!context.organizationId().equals(memory.policy().organizationId())
                || !context.tenantId().equals(memory.policy().tenantId())) {
            throw new IllegalArgumentException("memory is outside the execution context");
        }
        return memory;
    }

    private static void requirePermitted(MemoryRecord memory, MemoryGovernanceActor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.administrator()) return;
        if (memory.policy().scope() == MemoryPolicy.Scope.USER_PRIVATE
                && !actor.subjectId().equals(memory.policy().subjectId())) {
            throw new IllegalArgumentException("memory governance is not permitted for this actor");
        }
    }

    private static void requireAdministrator(MemoryGovernanceActor actor) {
        if (actor == null || !actor.administrator()) {
            throw new IllegalArgumentException("administrator permission is required for this operation");
        }
    }

    private boolean record(ExecutionContext context, MemoryRecord memory, MemoryGovernanceActor actor, String reason,
            String idempotencyKey, String operation) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Optional<MemoryGovernanceOperation> existing = repository.findOperation(idempotencyKey);
        if (existing.isPresent()) {
            MemoryGovernanceOperation value = existing.get();
            if (!value.memoryId().equals(memory.id()) || !value.operation().equals(operation)
                    || !value.organizationId().equals(context.organizationId())
                    || !value.tenantId().equals(context.tenantId())
                    || !value.reason().equals(reason.trim()) || !value.actor().equals(actor.subjectId())) {
                throw new IllegalArgumentException("memory governance idempotency key conflict");
            }
            return false;
        }
        repository.recordOperation(new MemoryGovernanceOperation(UUID.randomUUID(), memory.id(),
                context.organizationId(), context.tenantId(), operation, reason.trim(), actor.subjectId(),
                idempotencyKey.trim(), clock.instant()));
        return true;
    }

    private static MemoryPolicy withConsent(MemoryPolicy policy, MemoryPolicy.Consent consent) {
        return new MemoryPolicy(policy.scope(), policy.organizationId(), policy.tenantId(), policy.projectId(),
                policy.teamId(), policy.subjectId(), policy.sensitivity(), consent, policy.retention());
    }
}

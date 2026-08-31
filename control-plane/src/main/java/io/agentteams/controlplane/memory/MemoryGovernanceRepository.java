package io.agentteams.controlplane.memory;

import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for state-changing memory governance and its audit trail. */
public interface MemoryGovernanceRepository {
    Optional<MemoryRecord> findById(UUID memoryId, String organizationId, String tenantId);

    MemoryRecord save(MemoryRecord memory);

    Optional<MemoryGovernanceOperation> findOperation(String idempotencyKey);

    MemoryGovernanceOperation recordOperation(MemoryGovernanceOperation operation);
}

package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import java.time.Instant;
import java.util.UUID;

/** Metadata-only export; contentRef and summary are intentionally never included. */
public record MemoryGovernanceExport(UUID memoryId, MemoryPolicy.Scope scope, String source, Instant createdAt,
        Instant updatedAt, MemoryRecord.GovernanceStatus governanceStatus) {
    static MemoryGovernanceExport metadata(MemoryRecord memory) {
        return new MemoryGovernanceExport(memory.id(), memory.policy().scope(), memory.source(), memory.createdAt(),
                memory.updatedAt(), memory.governanceStatus());
    }
}

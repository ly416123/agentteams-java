package io.agentteams.controlplane.memory;

import java.util.List;

public interface MemoryRepository {
    MemoryRecord save(MemoryRecord memory);

    List<MemoryRecord> find(String organizationId, String tenantId);
}

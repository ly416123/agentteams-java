package io.agentteams.controlplane.memory;

import java.util.List;

public interface MemoryRepository {
    MemoryRecord save(MemoryRecord memory);

    List<MemoryRecord> find(String organizationId, String tenantId);

    /** Returns records visible to the supplied project at the persistence boundary. */
    default List<MemoryRecord> find(String organizationId, String tenantId, String projectId) {
        return find(organizationId, tenantId).stream()
                .filter(memory -> memory.policy().projectId() == null
                        || projectId.equals(memory.policy().projectId()))
                .toList();
    }
}

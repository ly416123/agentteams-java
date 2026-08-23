package io.agentteams.controlplane.agentspec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSpecRepository {
    void insert(AgentSpecRecord record);

    void updateLifecycle(AgentSpecRecord record, long expectedVersion);

    Optional<AgentSpecRecord> findById(UUID id);

    List<AgentSpecRecord> findAll();

    Optional<IdempotencyRecord> findIdempotency(String key);

    boolean insertIdempotency(IdempotencyRecord record);

    record IdempotencyRecord(String key, String requestHash, UUID specId, java.time.Instant createdAt) { }
}

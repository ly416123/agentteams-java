package io.agentteams.controlplane.template;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerTemplateRepository {
    boolean insertIdempotency(String key, String requestHash, UUID templateId, Instant createdAt);
    Optional<IdempotencyRecord> findIdempotency(String key);
    void insertTemplate(WorkerTemplate template);
    List<WorkerTemplate> findTemplates(String tenantId, String projectId);
    Optional<WorkerTemplate> findTemplate(UUID templateId);
    long nextRevision(UUID templateId);
    WorkerTemplateRevision createRevision(UUID templateId, long revision, String specJson, String digest,
            String actor, Instant now, String idempotencyKey);
    Optional<WorkerTemplateRevision> findRevision(UUID templateId, long revision);
    List<WorkerTemplateRevision> findRevisions(UUID templateId);
    WorkerTemplateRevision transition(UUID templateId, long revision, long expectedVersion,
            TemplateStatus expected, TemplateStatus next, String idempotencyKey);
    WorkerTemplateRevision publish(UUID templateId, long revision, long expectedVersion, String idempotencyKey);
    Optional<WorkerTemplateInstance> findInstanceByIdempotency(UUID templateId, String idempotencyKey);
    WorkerTemplateInstance insertInstance(WorkerTemplateInstance instance);
    WorkerTemplateInstance updateInstance(WorkerTemplateInstance instance, long expectedVersion);
    WorkerTemplateInstance upgradeInstance(WorkerTemplateInstance instance, long expectedVersion, long targetRevision,
            String idempotencyKey, String requestHash);
    Optional<WorkerTemplateInstance> findInstance(UUID templateId, UUID instanceId);
    List<WorkerTemplateInstance> findInstances(UUID templateId);

    record IdempotencyRecord(String key, String requestHash, UUID templateId, Instant createdAt) { }
}

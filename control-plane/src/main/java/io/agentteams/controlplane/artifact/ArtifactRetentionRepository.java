package io.agentteams.controlplane.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for retention policy resolution and deletion tombstones. */
public interface ArtifactRetentionRepository {
    List<ArtifactRetentionCandidate> findExpiredCandidates(Instant now, ArtifactRetentionPolicy fallback, int limit);

    boolean insertTombstone(ArtifactRetentionCandidate candidate, String storageKeyHash, Instant now,
            String status, String policyJson, String operator);

    Optional<ArtifactRetentionProjectPolicy> findProjectPolicy(String tenantId, String projectId);

    List<ArtifactRetentionTombstone> findDueTombstones(Instant now, int limit);

    void markDeleted(UUID tombstoneId, UUID artifactId, Instant now);

    void markHeld(UUID tombstoneId, Instant now);

    void markFailed(UUID tombstoneId, Instant now, Instant nextAttemptAt, String error);

    void upsertProjectPolicy(String tenantId, String projectId, ArtifactRetentionPolicy policy, Instant now);

    ArtifactRetentionProjectPolicy upsertProjectPolicy(String tenantId, String projectId,
            ArtifactRetentionPolicy policy, Instant now, long expectedVersion);

    void upsertTaskOverride(UUID taskId, ArtifactRetentionPolicy policy, Instant now);
}

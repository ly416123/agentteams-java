package io.agentteams.controlplane.project;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Optional<ProjectRecord> findProject(String tenantId, UUID projectId);

    void insertProject(ProjectRecord project);

    Optional<ProjectMembershipRecord> findMembership(String tenantId, UUID projectId, String subject);

    void upsertMembership(ProjectMembershipRecord membership);

    Optional<ProjectCreateIdempotency> findProjectCreateIdempotency(String tenantId, String key);

    boolean insertProjectCreateIdempotency(ProjectCreateIdempotency record);

    Optional<ProjectMembershipIdempotency> findMembershipIdempotency(String tenantId, UUID projectId, String key);

    boolean insertMembershipIdempotency(ProjectMembershipIdempotency record);

    record ProjectCreateIdempotency(String tenantId, String key, String requestHash, UUID projectId,
            Instant createdAt) { }

    record ProjectMembershipIdempotency(String tenantId, UUID projectId, String key, String requestHash,
            String subject, ProjectRole role, Instant createdAt) { }
}

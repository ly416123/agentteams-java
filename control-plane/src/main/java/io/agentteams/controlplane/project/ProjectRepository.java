package io.agentteams.controlplane.project;

import io.agentteams.controlplane.api.CursorPageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Optional<ProjectRecord> findProject(String tenantId, UUID projectId);

    Optional<ProjectRecord> findProjectByName(String tenantId, String name);

    List<ProjectRecord> findProjects(String tenantId, String actor, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction);

    void insertProject(ProjectRecord project);

    Optional<ProjectMembershipRecord> findMembership(String tenantId, UUID projectId, String subject);

    Optional<ProjectMembershipRecord> findMembershipIncludingInactive(String tenantId, UUID projectId,
            String subject);

    List<ProjectMembershipRecord> findMemberships(String tenantId, UUID projectId);

    void upsertMembership(ProjectMembershipRecord membership);

    boolean deactivateMembership(String tenantId, UUID projectId, String subject, Instant updatedAt);

    int countActiveOwners(String tenantId, UUID projectId);

    boolean transferOwnership(String tenantId, UUID projectId, String currentOwner, String newOwner,
            long expectedProjectVersion, Instant updatedAt);

    boolean updateMembershipStatus(String tenantId, UUID projectId, String subject, String status,
            long expectedVersion, Instant updatedAt);

    boolean updateMembershipRole(String tenantId, UUID projectId, String subject, ProjectRole role,
            long expectedVersion, Instant updatedAt);

    Optional<ProjectCreateIdempotency> findProjectCreateIdempotency(String tenantId, String key);

    boolean insertProjectCreateIdempotency(ProjectCreateIdempotency record);

    Optional<ProjectMembershipIdempotency> findMembershipIdempotency(String tenantId, UUID projectId, String key);

    boolean insertMembershipIdempotency(ProjectMembershipIdempotency record);

    record ProjectCreateIdempotency(String tenantId, String key, String requestHash, UUID projectId,
            Instant createdAt) { }

    record ProjectMembershipIdempotency(String tenantId, UUID projectId, String key, String requestHash,
            String subject, ProjectRole role, Instant createdAt) { }
}

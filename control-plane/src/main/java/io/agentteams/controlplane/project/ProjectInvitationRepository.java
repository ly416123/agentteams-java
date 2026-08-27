package io.agentteams.controlplane.project;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectInvitationRepository {
    Optional<ProjectRecord> findProject(String tenantId, UUID projectId);

    Optional<ProjectMembershipRecord> findMembership(String tenantId, UUID projectId, String subject);

    void insertInvitation(ProjectInvitationRecord invitation);

    Optional<InvitationIdempotency> findInvitationIdempotency(String tenantId, UUID projectId, String key);

    Optional<ProjectInvitationRecord> findInvitation(UUID invitationId);

    boolean insertInvitationIdempotency(InvitationIdempotency record);

    Optional<ProjectInvitationRecord> findInvitationByTokenHash(String tenantId, String tokenHash);

    boolean acceptInvitation(UUID invitationId, Instant acceptedAt);

    void upsertMembership(ProjectMembershipRecord membership);

    record InvitationIdempotency(String tenantId, UUID projectId, String key, String requestHash,
            UUID invitationId, Instant createdAt) { }
}

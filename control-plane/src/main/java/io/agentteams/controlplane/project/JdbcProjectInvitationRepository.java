package io.agentteams.controlplane.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectInvitationRepository implements ProjectInvitationRepository {
    private final JdbcTemplate jdbc;

    public JdbcProjectInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ProjectRecord> findProject(String tenantId, UUID projectId) {
        return jdbc.query("""
                SELECT id, tenant_id, name, status, created_by, created_at, updated_at, version
                  FROM projects WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new ProjectRecord(rs.getObject("id", UUID.class),
                rs.getString("tenant_id"), rs.getString("name"), rs.getString("status"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version")), tenantId, projectId).stream().findFirst();
    }

    @Override
    public Optional<ProjectMembershipRecord> findMembership(String tenantId, UUID projectId, String subject) {
        return jdbc.query("""
                SELECT tenant_id, project_id, subject, role, status, created_at, updated_at, version
                  FROM project_memberships
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE'
                """, (rs, row) -> new ProjectMembershipRecord(rs.getString("tenant_id"),
                rs.getObject("project_id", UUID.class), rs.getString("subject"),
                ProjectRole.valueOf(rs.getString("role")), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version")),
                tenantId, projectId, subject).stream().findFirst();
    }

    @Override
    public void insertInvitation(ProjectInvitationRecord invitation) {
        jdbc.update("""
                INSERT INTO project_invitations
                    (id, tenant_id, project_id, subject, role, token_hash, expires_at, created_by,
                     created_at, status, accepted_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, invitation.id(), invitation.tenantId(), invitation.projectId(), invitation.subject(),
                invitation.role().name(), invitation.tokenHash(), timestamp(invitation.expiresAt()),
                invitation.createdBy(), timestamp(invitation.createdAt()), invitation.status().name(),
                invitation.acceptedAt() == null ? null : timestamp(invitation.acceptedAt()), 0L);
    }

    @Override
    public Optional<ProjectInvitationRepository.InvitationIdempotency> findInvitationIdempotency(
            String tenantId, UUID projectId, String key) {
        return jdbc.query("""
                SELECT tenant_id, project_id, idempotency_key, request_hash, invitation_id, created_at
                  FROM project_invitation_idempotency
                 WHERE tenant_id = ? AND project_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ProjectInvitationRepository.InvitationIdempotency(
                rs.getString("tenant_id"), rs.getObject("project_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("request_hash"),
                rs.getObject("invitation_id", UUID.class), instant(rs, "created_at")),
                tenantId, projectId, key).stream().findFirst();
    }

    @Override
    public Optional<ProjectInvitationRecord> findInvitation(UUID invitationId) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, subject, role, token_hash, expires_at, created_by,
                       created_at, status, accepted_at
                  FROM project_invitations WHERE id = ?
                """, (rs, row) -> invitation(rs), invitationId).stream().findFirst();
    }

    @Override
    public boolean insertInvitationIdempotency(ProjectInvitationRepository.InvitationIdempotency record) {
        return jdbc.update("""
                INSERT INTO project_invitation_idempotency
                    (tenant_id, project_id, idempotency_key, request_hash, invitation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, idempotency_key) DO NOTHING
                """, record.tenantId(), record.projectId(), record.key(), record.requestHash(),
                record.invitationId(), timestamp(record.createdAt())) == 1;
    }

    @Override
    public Optional<ProjectInvitationRecord> findInvitationByTokenHash(String tenantId, String tokenHash) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, subject, role, token_hash, expires_at, created_by,
                       created_at, status, accepted_at
                  FROM project_invitations WHERE tenant_id = ? AND token_hash = ?
                """, (rs, row) -> invitation(rs), tenantId, tokenHash).stream().findFirst();
    }

    @Override
    public boolean acceptInvitation(UUID invitationId, Instant acceptedAt) {
        return jdbc.update("""
                UPDATE project_invitations
                   SET status = 'ACCEPTED', accepted_at = ?, version = version + 1
                 WHERE id = ? AND status = 'INVITED' AND expires_at > ?
                """, timestamp(acceptedAt), invitationId, timestamp(acceptedAt)) == 1;
    }

    @Override
    public void upsertMembership(ProjectMembershipRecord membership) {
        jdbc.update("""
                INSERT INTO project_memberships
                    (tenant_id, project_id, subject, role, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, subject) DO UPDATE
                    SET role = EXCLUDED.role, status = EXCLUDED.status, updated_at = EXCLUDED.updated_at,
                        version = project_memberships.version + 1
                """, membership.tenantId(), membership.projectId(), membership.subject(),
                membership.role().name(), membership.status(), timestamp(membership.createdAt()),
                timestamp(membership.updatedAt()), membership.version());
    }

    private static ProjectInvitationRecord invitation(ResultSet rs) throws SQLException {
        Timestamp accepted = rs.getTimestamp("accepted_at");
        return new ProjectInvitationRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getObject("project_id", UUID.class), rs.getString("subject"),
                ProjectRole.valueOf(rs.getString("role")), rs.getString("token_hash"),
                instant(rs, "expires_at"), rs.getString("created_by"), instant(rs, "created_at"),
                ProjectInvitationRecord.Status.valueOf(rs.getString("status")),
                accepted == null ? null : accepted.toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return Objects.requireNonNull(value, column + " must not be null").toInstant();
    }
}

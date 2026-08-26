package io.agentteams.controlplane.team;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL persistence for immutable Team revisions and atomic lifecycle transitions. */
public class TeamRevisionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TeamRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource(), "jdbc data source")));
    }

    /** Kept for source compatibility; callers must use createDraft so allocation and writes share a transaction. */
    @Deprecated
    public long nextRevision(UUID teamId) {
        return lockedNextRevision(teamId);
    }

    public TeamRevision createDraft(UUID teamId, UUID leaderAgentId, String overlay, String digest,
            Long rollbackOfRevision, String actor, Instant now, List<UUID> memberAgentIds, String idempotencyKey) {
        requireKey(idempotencyKey);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transaction.execute(status -> {
                    Optional<TeamRevision> existing = findByIdempotencyKey(teamId, idempotencyKey);
                    if (existing.isPresent()) return existing.get();
                    jdbc.update("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", teamId.toString());
                    long revision = lockedNextRevision(teamId);
                    jdbc.update("""
                            INSERT INTO team_revisions(team_id, revision, leader_agent_id, overlay, digest, status,
                                rollback_of_revision, created_by, created_at, version, idempotency_key)
                            VALUES (?, ?, ?, CAST(? AS jsonb), ?, 'DRAFT', ?, ?, ?, 0, ?)
                            """, teamId, revision, leaderAgentId, overlay, digest, rollbackOfRevision, actor,
                            java.sql.Timestamp.from(now), idempotencyKey);
                    insertMembers(teamId, revision, memberAgentIds);
                    return find(teamId, revision).orElseThrow();
                });
            } catch (DuplicateKeyException conflict) {
                Optional<TeamRevision> existing = findByIdempotencyKey(teamId, idempotencyKey);
                if (existing.isPresent()) return existing.get();
                if (attempt == 2) throw conflict;
            }
        }
        throw new IllegalStateException("revision allocation did not converge");
    }

    public TeamRevision createRollback(UUID teamId, TeamRevision target, String actor, Instant now,
            String idempotencyKey) {
        return createDraft(teamId, target.leaderAgentId(), target.overlayJson(), target.digest(), target.revision(),
                actor, now, target.memberAgentIds(), idempotencyKey);
    }

    public Optional<TeamRevision> find(UUID teamId, long revision) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? AND revision = ?
                """, this::map, teamId, revision).stream().findFirst();
    }

    public List<TeamRevision> findAll(UUID teamId) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? ORDER BY revision
                """, this::map, teamId);
    }

    public Optional<TeamRevision> currentPublished(UUID teamId) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? AND status = 'PUBLISHED'
                 ORDER BY revision DESC LIMIT 1
                """, this::map, teamId).stream().findFirst();
    }

    /** Atomic review/publish transition with a durable operation key. */
    public TeamRevision transition(UUID teamId, long revision, long expectedVersion,
            TeamRevisionStatus expectedStatus, TeamRevisionStatus nextStatus, String idempotencyKey) {
        requireKey(idempotencyKey);
        return transaction.execute(status -> {
            Optional<TeamRevision> replay = operationResult(teamId, revision, "TRANSITION", idempotencyKey);
            if (replay.isPresent()) return replay.get();
            TeamRevision current = locked(teamId, revision);
            if (current.status() != expectedStatus || current.version() != expectedVersion) {
                throw new TeamRevisionConflictException("team revision version or status is stale");
            }
            int updated = jdbc.update("""
                    UPDATE team_revisions SET status = ?, version = version + 1
                     WHERE team_id = ? AND revision = ? AND status = ? AND version = ?
                    """, nextStatus.name(), teamId, revision, expectedStatus.name(), expectedVersion);
            if (updated != 1) throw new TeamRevisionConflictException("team revision version is stale");
            recordOperation(teamId, revision, "TRANSITION", idempotencyKey);
            return find(teamId, revision).orElseThrow();
        });
    }

    /** Publishes using one transaction and one CAS boundary; failed target CAS cannot deprecate the old revision. */
    public TeamRevision publish(UUID teamId, long revision, long expectedVersion, String idempotencyKey) {
        requireKey(idempotencyKey);
        return transaction.execute(status -> {
            Optional<TeamRevision> replay = operationResult(teamId, revision, "PUBLISH", idempotencyKey);
            if (replay.isPresent()) return replay.get();
            jdbc.queryForObject("SELECT current_revision FROM teams WHERE id = ? FOR UPDATE", Long.class, teamId);
            TeamRevision current = locked(teamId, revision);
            if ((current.status() != TeamRevisionStatus.DRAFT && current.status() != TeamRevisionStatus.REVIEWING)
                    || current.version() != expectedVersion) {
                throw new TeamRevisionConflictException("team revision version or status is stale");
            }
            // The partial unique index requires the old row to be retired first. Both writes are in this
            // transaction, so a failed target CAS rolls the retirement back and never exposes a gap.
            jdbc.update("""
                    UPDATE team_revisions SET status = 'DEPRECATED', version = version + 1
                     WHERE team_id = ? AND status = 'PUBLISHED' AND revision <> ?
                    """, teamId, revision);
            int updated = jdbc.update("""
                    UPDATE team_revisions SET status = 'PUBLISHED', version = version + 1
                     WHERE team_id = ? AND revision = ? AND version = ?
                       AND status IN ('DRAFT', 'REVIEWING')
                    """, teamId, revision, expectedVersion);
            if (updated != 1) throw new TeamRevisionConflictException("team revision publish CAS failed");
            jdbc.update("UPDATE teams SET current_revision = ? WHERE id = ?", revision, teamId);
            recordOperation(teamId, revision, "PUBLISH", idempotencyKey);
            return find(teamId, revision).orElseThrow();
        });
    }

    public void validatePublish(TeamRevision revision) {
        boolean activeTeam = jdbc.query("SELECT 1 FROM teams WHERE id = ? AND status = 'ACTIVE'",
                (rs, row) -> true, revision.teamId()).stream().findFirst().orElse(false);
        if (!activeTeam) throw new TeamRevisionConflictException("team must be ACTIVE before publish");
        boolean activeLeader = jdbc.query("""
                SELECT 1 FROM team_memberships WHERE team_id = ? AND agent_id = ? AND status = 'ACTIVE'
                """, (rs, row) -> true, revision.teamId(), revision.leaderAgentId()).stream()
                .findFirst().orElse(false);
        if (!activeLeader) throw new TeamRevisionConflictException("leader must be an ACTIVE Team member");
        String placeholders = String.join(", ", java.util.Collections.nCopies(revision.memberAgentIds().size(), "?"));
        Object[] args = new Object[revision.memberAgentIds().size() + 1];
        args[0] = revision.teamId();
        for (int i = 0; i < revision.memberAgentIds().size(); i++) args[i + 1] = revision.memberAgentIds().get(i);
        Long activeMembers = jdbc.queryForObject("""
                SELECT count(*) FROM team_memberships
                 WHERE team_id = ? AND status = 'ACTIVE' AND agent_id IN (""" + placeholders + ")", Long.class, args);
        if (activeMembers == null || activeMembers != revision.memberAgentIds().stream().distinct().count()) {
            throw new TeamRevisionConflictException("all revision members must be ACTIVE Team members");
        }
    }

    /** Legacy insertion API; new services must use createDraft. */
    @Deprecated
    public TeamRevision insert(TeamRevision revision, String idempotencyKey) {
        return createDraft(revision.teamId(), revision.leaderAgentId(), revision.overlayJson(), revision.digest(),
                revision.rollbackOfRevision(), revision.createdBy(), revision.createdAt(), revision.memberAgentIds(),
                idempotencyKey);
    }

    public Optional<TeamRevision> findByIdempotencyKey(UUID teamId, String idempotencyKey) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? AND idempotency_key = ?
                """, this::map, teamId, idempotencyKey).stream().findFirst();
    }

    @Deprecated
    public TeamRevision update(TeamRevision revision) {
        int updated = jdbc.update("""
                UPDATE team_revisions SET overlay = CAST(? AS jsonb), digest = ?, status = ?,
                    leader_agent_id = ?, version = ?
                  WHERE team_id = ? AND revision = ? AND version = ?
                """, revision.overlayJson(), revision.digest(), revision.status().name(), revision.leaderAgentId(),
                revision.version() + 1, revision.teamId(), revision.revision(), revision.version());
        if (updated == 0) throw new TeamRevisionConflictException("team revision version is stale");
        return find(revision.teamId(), revision.revision()).orElseThrow();
    }

    @Deprecated
    public void deprecatePublished(UUID teamId, long exceptRevision) {
        throw new UnsupportedOperationException("publish must use atomic publish CAS");
    }

    private TeamRevision locked(UUID teamId, long revision) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? AND revision = ? FOR UPDATE
                """, this::map, teamId, revision).stream().findFirst()
                .orElseThrow(() -> new TeamRevisionConflictException("team revision does not exist"));
    }

    private long lockedNextRevision(UUID teamId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) + 1 FROM team_revisions WHERE team_id = ?",
                Long.class, teamId);
        return value == null ? 1 : value;
    }

    private void insertMembers(UUID teamId, long revision, List<UUID> memberAgentIds) {
        for (int index = 0; index < memberAgentIds.size(); index++) {
            jdbc.update("""
                    INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index)
                    VALUES (?, ?, ?, ?)
                    """, teamId, revision, memberAgentIds.get(index), index);
        }
    }

    private Optional<TeamRevision> operationResult(UUID teamId, long revision, String operation, String key) {
        return jdbc.query("""
                SELECT result_revision FROM team_revision_operations
                 WHERE team_id = ? AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> rs.getLong(1), teamId, operation, key).stream()
                .findFirst().map(result -> {
                    if (result != revision) throw new TeamRevisionConflictException("Idempotency-Key belongs to another revision");
                    return find(teamId, result).orElseThrow();
                });
    }

    private void recordOperation(UUID teamId, long revision, String operation, String key) {
        jdbc.update("""
                INSERT INTO team_revision_operations(team_id, operation, idempotency_key, result_revision, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, teamId, operation, key, revision);
    }

    private TeamRevision map(ResultSet rs, int row) throws SQLException {
        UUID teamId = rs.getObject("team_id", UUID.class);
        UUID leader = rs.getObject("leader_agent_id", UUID.class);
        long revision = rs.getLong("revision");
        return new TeamRevision(teamId, revision, leader, rs.getString("overlay"), rs.getString("digest"),
                TeamRevisionStatus.valueOf(rs.getString("status")), (Long) rs.getObject("rollback_of_revision"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getLong("version"),
                members(teamId, revision, leader));
    }

    private List<UUID> members(UUID teamId, long revision, UUID leader) {
        List<UUID> result = jdbc.query("""
                SELECT agent_id FROM team_revision_members
                 WHERE team_id = ? AND team_revision = ? ORDER BY member_index
                """, (rs, row) -> rs.getObject("agent_id", UUID.class), teamId, revision);
        return result.isEmpty() ? List.of(leader) : result;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    }
}

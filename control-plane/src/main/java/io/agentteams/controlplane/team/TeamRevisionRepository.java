package io.agentteams.controlplane.team;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL persistence for immutable Team revisions. */
public class TeamRevisionRepository {
    private final JdbcTemplate jdbc;

    public TeamRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    public long nextRevision(UUID teamId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) + 1 FROM team_revisions WHERE team_id = ?",
                Long.class, teamId);
        return value == null ? 1 : value;
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

    public TeamRevision insert(TeamRevision revision, String idempotencyKey) {
        Optional<TeamRevision> existing = findByIdempotencyKey(revision.teamId(), idempotencyKey);
        if (existing.isPresent()) return existing.get();
        jdbc.update("""
                INSERT INTO team_revisions(team_id, revision, leader_agent_id, overlay, digest, status,
                    rollback_of_revision, created_by, created_at, version, idempotency_key)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, revision.teamId(), revision.revision(), revision.leaderAgentId(), revision.overlayJson(),
                revision.digest(), revision.status().name(), revision.rollbackOfRevision(), revision.createdBy(),
                java.sql.Timestamp.from(revision.createdAt()), revision.version(), idempotencyKey);
        for (int index = 0; index < revision.memberAgentIds().size(); index++) {
            jdbc.update("""
                    INSERT INTO team_revision_members(team_id, team_revision, agent_id, member_index)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (team_id, team_revision, agent_id) DO NOTHING
                    """, revision.teamId(), revision.revision(), revision.memberAgentIds().get(index), index);
        }
        return find(revision.teamId(), revision.revision()).orElseThrow();
    }

    public Optional<TeamRevision> findByIdempotencyKey(UUID teamId, String idempotencyKey) {
        return jdbc.query("""
                SELECT team_id, revision, leader_agent_id, overlay::text, digest, status,
                       rollback_of_revision, created_by, created_at, version
                  FROM team_revisions WHERE team_id = ? AND idempotency_key = ?
                """, this::map, teamId, idempotencyKey).stream().findFirst();
    }

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

    public void deprecatePublished(UUID teamId, long exceptRevision) {
        jdbc.update("UPDATE team_revisions SET status = 'DEPRECATED' WHERE team_id = ? AND status = 'PUBLISHED'"
                + " AND revision <> ?", teamId, exceptRevision);
        jdbc.update("UPDATE teams SET current_revision = ? WHERE id = ?", exceptRevision, teamId);
    }

    private TeamRevision map(ResultSet rs, int row) throws SQLException {
        UUID teamId = rs.getObject("team_id", UUID.class);
        UUID leader = rs.getObject("leader_agent_id", UUID.class);
        long revision = rs.getLong("revision");
        return new TeamRevision(teamId, revision, leader, rs.getString("overlay"),
                rs.getString("digest"), TeamRevisionStatus.valueOf(rs.getString("status")),
                (Long) rs.getObject("rollback_of_revision"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"), members(teamId, revision, leader));
    }

    private List<UUID> members(UUID teamId, long revision, UUID leader) {
        List<UUID> result = jdbc.query("""
                SELECT agent_id FROM team_revision_members
                 WHERE team_id = ? AND team_revision = ? ORDER BY member_index
                """, (rs, row) -> rs.getObject("agent_id", UUID.class), teamId, revision);
        return result.isEmpty() ? List.of(leader) : result;
    }
}

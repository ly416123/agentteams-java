package io.agentteams.controlplane.team;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL persistence for deployment aggregates and independent member bindings. */
public class TeamDeploymentRepository {
    private final JdbcTemplate jdbc;

    public TeamDeploymentRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    public TeamDeployment create(TeamDeployment deployment) {
        Optional<TeamDeployment> existing = findByIdempotencyKey(deployment.teamId(), deployment.idempotencyKey());
        if (existing.isPresent()) return existing.get();
        jdbc.update("""
                INSERT INTO team_deployments(id, team_id, team_revision, status, created_at, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
        """, deployment.id(), deployment.teamId(), deployment.teamRevision(), deployment.status(),
                java.sql.Timestamp.from(deployment.createdAt()), deployment.idempotencyKey());
        for (TeamDeployment.Member member : deployment.members()) {
            jdbc.update("""
                    INSERT INTO team_deployment_members(deployment_id, agent_id, base_manifest, task_overlay,
                        binding_id, status, failure_code)
                    VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                    ON CONFLICT (deployment_id, agent_id) DO NOTHING
                    """, deployment.id(), member.agentId(), nullableJson(member.baseManifest()),
                    member.taskOverlay() == null ? "{}" : member.taskOverlay(), member.bindingId(), member.status(),
                    member.failureCode());
        }
        return findByIdempotencyKey(deployment.teamId(), deployment.idempotencyKey())
                .or(() -> find(deployment.id())).orElseThrow();
    }

    public Optional<TeamDeployment> find(UUID deploymentId) {
        List<TeamDeployment> deployments = jdbc.query("""
                SELECT id, team_id, team_revision, status, created_at, idempotency_key
                  FROM team_deployments WHERE id = ?
                """, (rs, row) -> mapDeployment(rs), deploymentId);
        if (deployments.isEmpty()) return Optional.empty();
        TeamDeployment deployment = deployments.get(0);
        return Optional.of(new TeamDeployment(deployment.id(), deployment.teamId(), deployment.teamRevision(),
                deployment.status(), members(deployment.id()), deployment.createdAt(), deployment.idempotencyKey()));
    }

    public Optional<TeamDeployment> findByIdempotencyKey(UUID teamId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, team_id, team_revision, status, created_at, idempotency_key
                  FROM team_deployments WHERE team_id = ? AND idempotency_key = ?
                """, (rs, row) -> mapDeployment(rs), teamId, idempotencyKey).stream().findFirst()
                .map(deployment -> new TeamDeployment(deployment.id(), deployment.teamId(), deployment.teamRevision(),
                        deployment.status(), members(deployment.id()), deployment.createdAt(), deployment.idempotencyKey()));
    }

    public List<TeamDeployment.Member> failedMembers(UUID deploymentId) {
        return jdbc.query("""
                SELECT agent_id, base_manifest::text, task_overlay::text, binding_id, status, failure_code
                  FROM team_deployment_members WHERE deployment_id = ? AND status = 'FAILED'
                 ORDER BY agent_id
                """, this::mapMember, deploymentId);
    }

    public void markRetrying(UUID deploymentId, List<UUID> agentIds, Instant at) {
        if (agentIds == null || agentIds.isEmpty()) return;
        String placeholders = String.join(", ", java.util.Collections.nCopies(agentIds.size(), "?"));
        Object[] arguments = new Object[agentIds.size() + 1];
        arguments[0] = deploymentId;
        for (int index = 0; index < agentIds.size(); index++) arguments[index + 1] = agentIds.get(index);
        jdbc.update("UPDATE team_deployment_members SET status = 'PENDING', failure_code = NULL"
                + " WHERE deployment_id = ? AND agent_id IN (" + placeholders + ") AND status = 'FAILED'", arguments);
        updateStatus(deploymentId, "PENDING");
    }

    public void markMember(UUID deploymentId, UUID agentId, UUID bindingId, String status, String failureCode) {
        jdbc.update("""
                UPDATE team_deployment_members SET binding_id = ?, status = ?, failure_code = ?
                  WHERE deployment_id = ? AND agent_id = ?
                """, bindingId, status, failureCode, deploymentId, agentId);
    }

    public void markMemberStatus(UUID deploymentId, UUID agentId, String status, String failureCode) {
        jdbc.update("""
                UPDATE team_deployment_members SET status = ?, failure_code = ?
                  WHERE deployment_id = ? AND agent_id = ?
                """, status, failureCode, deploymentId, agentId);
    }

    public void updateStatus(UUID deploymentId, String status) {
        jdbc.update("UPDATE team_deployments SET status = ? WHERE id = ?", status, deploymentId);
    }

    public void refreshStatus(UUID deploymentId) {
        long pending = count(deploymentId, "PENDING");
        long failed = count(deploymentId, "FAILED");
        if (pending == 0) updateStatus(deploymentId, failed == 0 ? "SUCCEEDED" : "PARTIAL_FAILURE");
    }

    private List<TeamDeployment.Member> members(UUID deploymentId) {
        return jdbc.query("""
                SELECT agent_id, base_manifest::text, task_overlay::text, binding_id, status, failure_code
                  FROM team_deployment_members WHERE deployment_id = ? ORDER BY agent_id
                """, this::mapMember, deploymentId);
    }

    private TeamDeployment mapDeployment(ResultSet rs) throws SQLException {
        return new TeamDeployment(rs.getObject("id", UUID.class), rs.getObject("team_id", UUID.class),
                rs.getLong("team_revision"), rs.getString("status"), List.of(),
                rs.getTimestamp("created_at").toInstant(), rs.getString("idempotency_key"));
    }

    private TeamDeployment.Member mapMember(ResultSet rs, int row) throws SQLException {
        return new TeamDeployment.Member(rs.getObject("agent_id", UUID.class), rs.getString("base_manifest"),
                rs.getString("task_overlay"), rs.getObject("binding_id", UUID.class), rs.getString("status"),
                rs.getString("failure_code"));
    }

    private static String nullableJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private long count(UUID deploymentId, String status) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM team_deployment_members"
                + " WHERE deployment_id = ? AND status = ?", Long.class, deploymentId, status);
        return value == null ? 0 : value;
    }
}

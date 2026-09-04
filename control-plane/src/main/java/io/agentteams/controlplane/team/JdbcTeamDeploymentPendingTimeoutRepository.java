package io.agentteams.controlplane.team;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL read/write of the team deployment PENDING timeout. Age is measured on the shared
 * config_apply_records row rather than on presence, so an agent that is online but never
 * acknowledges still converges to a terminal state.
 */
public final class JdbcTeamDeploymentPendingTimeoutRepository implements TeamDeploymentPendingTimeoutRepository {
    private final JdbcTemplate jdbc;
    private final TeamDeploymentRepository deployments;

    public JdbcTeamDeploymentPendingTimeoutRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.deployments = new TeamDeploymentRepository(jdbc);
    }

    @Override
    public int failStalePendingMembers(Instant now, Instant applyUpdatedBefore, int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(applyUpdatedBefore, "applyUpdatedBefore");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        // The apply record is keyed by binding, so every deployment that shares the frozen binding of a
        // dead rollout times out together, and the aggregate refresh runs once per touched deployment.
        List<UUID> deploymentIds = jdbc.query("""
                WITH stale AS (
                    SELECT member.deployment_id, member.agent_id
                      FROM team_deployment_members member
                      JOIN config_apply_records apply ON apply.binding_id = member.binding_id
                                                AND apply.agent_id = member.agent_id
                     WHERE member.status = 'PENDING'
                       AND apply.updated_at < ?
                       AND apply.phase NOT IN ('SUCCEEDED', 'FAILED')
                     ORDER BY member.deployment_id, member.agent_id
                     LIMIT ?
                     FOR UPDATE OF member SKIP LOCKED
                )
                UPDATE team_deployment_members member
                   SET status = 'FAILED', failure_code = 'APPLY_TIMEOUT'
                  FROM stale
                 WHERE member.deployment_id = stale.deployment_id AND member.agent_id = stale.agent_id
                RETURNING member.deployment_id
                """, (rs, row) -> rs.getObject("deployment_id", UUID.class),
                Timestamp.from(applyUpdatedBefore), limit)
                .stream().distinct().toList();
        deploymentIds.forEach(deployments::refreshStatus);
        return deploymentIds.size();
    }
}

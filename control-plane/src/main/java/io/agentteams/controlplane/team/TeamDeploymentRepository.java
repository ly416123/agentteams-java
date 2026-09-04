package io.agentteams.controlplane.team;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL persistence for deployment aggregates and independent member bindings. */
public class TeamDeploymentRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TeamDeploymentRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource(), "jdbc data source")));
    }

    public TeamDeployment create(TeamDeployment deployment) {
        return transaction.execute(status -> {
            Optional<TeamDeployment> existing = findByIdempotencyKey(deployment.teamId(), deployment.idempotencyKey());
            if (existing.isPresent()) return existing.get();
            jdbc.update("""
                    INSERT INTO team_deployments(id, team_id, team_revision, status, version, created_at, idempotency_key)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
            """, deployment.id(), deployment.teamId(), deployment.teamRevision(), deployment.status(),
                    java.sql.Timestamp.from(deployment.createdAt()), deployment.idempotencyKey());
            for (TeamDeployment.Member member : deployment.members()) {
                jdbc.update("""
                        INSERT INTO team_deployment_members(deployment_id, agent_id, base_manifest, task_overlay,
                            binding_id, status, failure_code)
                        VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                        """, deployment.id(), member.agentId(), nullableJson(member.baseManifest()),
                        member.taskOverlay() == null ? "{}" : member.taskOverlay(), member.bindingId(), member.status(),
                        member.failureCode());
            }
            return find(deployment.id()).orElseThrow();
        });
    }

    public Optional<TeamDeployment> find(UUID deploymentId) {
        List<TeamDeployment> deployments = jdbc.query("""
                SELECT id, team_id, team_revision, status, version, created_at, idempotency_key
                  FROM team_deployments WHERE id = ?
                """, (rs, row) -> mapDeployment(rs), deploymentId);
        if (deployments.isEmpty()) return Optional.empty();
        TeamDeployment deployment = deployments.get(0);
        return Optional.of(new TeamDeployment(deployment.id(), deployment.teamId(), deployment.teamRevision(),
                deployment.status(), members(deployment.id()), deployment.createdAt(), deployment.idempotencyKey(),
                deployment.version()));
    }

    public List<TeamDeployment> list(UUID teamId) {
        return jdbc.query("""
                SELECT id, team_id, team_revision, status, version, created_at, idempotency_key
                  FROM team_deployments WHERE team_id = ?
                 ORDER BY created_at DESC, id DESC
                """, (rs, row) -> mapDeployment(rs), java.util.Objects.requireNonNull(teamId, "teamId"))
                .stream()
                .map(deployment -> new TeamDeployment(deployment.id(), deployment.teamId(),
                        deployment.teamRevision(), deployment.status(), members(deployment.id()),
                        deployment.createdAt(), deployment.idempotencyKey(), deployment.version()))
                .toList();
    }

    public Optional<TeamDeployment> findByIdempotencyKey(UUID teamId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, team_id, team_revision, status, version, created_at, idempotency_key
                  FROM team_deployments WHERE team_id = ? AND idempotency_key = ?
                """, (rs, row) -> mapDeployment(rs), teamId, idempotencyKey).stream().findFirst()
                .map(deployment -> new TeamDeployment(deployment.id(), deployment.teamId(), deployment.teamRevision(),
                        deployment.status(), members(deployment.id()), deployment.createdAt(), deployment.idempotencyKey(),
                        deployment.version()));
    }

    public Optional<UUID> findTeamIdByBinding(UUID bindingId, UUID agentId) {
        return jdbc.query("""
                SELECT deployment.team_id
                  FROM team_deployment_members member
                  JOIN team_deployments deployment ON deployment.id = member.deployment_id
                 WHERE member.binding_id = ? AND member.agent_id = ?
                """, (rs, row) -> rs.getObject("team_id", UUID.class), bindingId, agentId)
                .stream().findFirst();
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

    public void markRetrying(UUID deploymentId, List<UUID> agentIds, Instant at, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        boolean recorded = jdbc.update("""
                INSERT INTO team_deployment_operations(deployment_id, operation, idempotency_key, request_hash, created_at)
                VALUES (?, 'RETRY', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, deploymentId, idempotencyKey, "legacy-" + deploymentId, java.sql.Timestamp.from(at)) == 1;
        if (recorded) markRetrying(deploymentId, agentIds, at);
    }

    public boolean claimRetry(UUID deploymentId, List<UUID> agentIds, long expectedVersion,
            String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (agentIds == null || agentIds.isEmpty()) return false;
        return transaction.execute(status -> {
            List<String> previousHashes = jdbc.query("""
                    SELECT request_hash FROM team_deployment_operations
                     WHERE deployment_id = ? AND operation = 'RETRY' AND idempotency_key = ?
                    """, (rs, row) -> rs.getString(1), deploymentId, idempotencyKey);
            if (!previousHashes.isEmpty()) {
                if (!java.util.Objects.equals(previousHashes.get(0), requestHash)) {
                    throw new TeamRevisionConflictException("Idempotency-Key request hash mismatch");
                }
                return false;
            }
            long actual = jdbc.queryForObject("SELECT version FROM team_deployments WHERE id = ? FOR UPDATE",
                    Long.class, deploymentId);
            if (actual != expectedVersion) throw new TeamRevisionConflictException("deployment version is stale");
            String placeholders = String.join(", ", java.util.Collections.nCopies(agentIds.size(), "?"));
            Object[] args = new Object[agentIds.size() + 1];
            args[0] = deploymentId;
            for (int index = 0; index < agentIds.size(); index++) args[index + 1] = agentIds.get(index);
            jdbc.update("UPDATE team_deployment_members SET status = 'PENDING', failure_code = NULL"
                    + " WHERE deployment_id = ? AND agent_id IN (" + placeholders + ") AND status = 'FAILED'", args);
            int updated = jdbc.update("""
                    UPDATE team_deployments SET status = 'PENDING', version = version + 1
                     WHERE id = ? AND version = ?
                    """, deploymentId, expectedVersion);
            if (updated != 1) throw new TeamRevisionConflictException("deployment retry CAS failed");
            jdbc.update("""
                    INSERT INTO team_deployment_operations(deployment_id, operation, idempotency_key,
                        request_hash, created_at) VALUES (?, 'RETRY', ?, ?, CURRENT_TIMESTAMP)
                    """, deploymentId, idempotencyKey, requestHash);
            return true;
        });
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

    /** A ConfigApplied ACK can advance only the still-pending member of its frozen deployment binding. */
    public void recordConfigAppliedAck(io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand command) {
        recordConfigAppliedAck(command, command.configVersion());
    }

    public void recordConfigAppliedAck(io.agentteams.application.api.ConfigEventPort.ConfigAppliedCommand command,
            long applyGeneration) {
        if (command.configVersion() != applyGeneration) {
            throw new TeamRevisionConflictException("ConfigApplied generation is stale");
        }
        String status = command.applied() ? "SUCCEEDED" : "FAILED";
        // A ConfigApplied ACK can advance the still-pending member of every deployment that shares its
        // frozen binding, because a Team revision binding is keyed by team, revision and agent rather than
        // by deployment, so repeated deployments of one revision acknowledge the same binding.
        List<UUID> advanced = jdbc.queryForList("""
                UPDATE team_deployment_members member
                   SET status = ?, failure_code = ?
                  FROM team_deployments deployment
                 WHERE member.deployment_id = deployment.id
                   AND member.binding_id = ? AND member.agent_id = ?
                   AND EXISTS (SELECT 1 FROM config_bindings binding
                                JOIN config_snapshots snapshot ON snapshot.id = binding.snapshot_id
                               WHERE binding.id = member.binding_id
                                 AND binding.snapshot_id = ? AND snapshot.version = ?)
                   AND EXISTS (SELECT 1 FROM config_apply_records apply
                                WHERE apply.id = ? AND apply.binding_id = member.binding_id
                                  AND apply.snapshot_id = ? AND apply.observed_version = ?)
                   AND member.status IN ('PENDING', 'FAILED')
                   AND deployment.status IN ('PENDING', 'PARTIAL_FAILURE', 'FAILED')
                RETURNING member.deployment_id
                """, UUID.class, status, command.applied() ? null : command.errorMessage(),
                command.bindingId(), command.agentId(),
                command.snapshotId(), command.configVersion(), command.eventId(), command.snapshotId(),
                applyGeneration);
        advanced.forEach(this::refreshStatus);
    }

    public void updateStatus(UUID deploymentId, String status) {
        jdbc.update("UPDATE team_deployments SET status = ? WHERE id = ?", status, deploymentId);
    }

    public void refreshStatus(UUID deploymentId) {
        long pending = count(deploymentId, "PENDING");
        long failed = count(deploymentId, "FAILED");
        long succeeded = count(deploymentId, "SUCCEEDED");
        if (pending == 0) updateStatus(deploymentId,
                failed == 0 ? "SUCCEEDED" : (succeeded == 0 ? "FAILED" : "PARTIAL_FAILURE"));
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
                rs.getTimestamp("created_at").toInstant(), rs.getString("idempotency_key"), rs.getLong("version"));
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

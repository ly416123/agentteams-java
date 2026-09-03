package io.agentteams.controlplane.persistence;

import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AgentRepository {

    private final JdbcTemplate jdbc;

    AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AgentRecord agent) {
        jdbc.update("""
                INSERT INTO agents
                    (id, name, worker_type, phase, runtime, capabilities, metadata, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, agent.id(), agent.name(), agent.workerType().name(), agent.phase().name(), agent.runtime(),
                JdbcSupport.json(agent.capabilitiesJson()), JdbcSupport.json(agent.metadataJson()),
                JdbcSupport.timestamp(agent.createdAt()), JdbcSupport.timestamp(agent.updatedAt()), agent.version());
    }

    public Optional<AgentRecord> findById(UUID id) {
        return jdbc.query("""
                SELECT a.id, a.name, a.worker_type, a.phase, a.runtime, a.capabilities::text, a.metadata::text,
                       a.created_at, a.updated_at, a.version, template.template_name
                  FROM agents a
                  LEFT JOIN LATERAL (
                       SELECT COALESCE(t.display_name, t.name) AS template_name
                         FROM worker_template_instances i
                         JOIN worker_templates t ON t.id = i.template_id
                        WHERE i.worker_id = a.id AND i.status = 'SUCCEEDED'
                        ORDER BY i.updated_at DESC, i.id DESC
                        LIMIT 1
                  ) template ON TRUE
                 WHERE a.id = ?
                """, this::mapWithTemplate, id).stream().findFirst();
    }

    public Optional<AgentRecord> findByIdForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id, name, worker_type, phase, runtime, capabilities::text, metadata::text,
                       created_at, updated_at, version
                  FROM agents WHERE id = ? FOR UPDATE
                """, this::map, id).stream().findFirst();
    }

    public java.util.List<AgentRecord> findPage(Principal principal, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction) {
        return findPage(principal, after, limit, direction, null, null);
    }

    public java.util.List<AgentRecord> findPage(Principal principal, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction, String status, String query) {
        String order = direction == CursorPageRequest.Direction.ASC
                ? " ORDER BY a.updated_at ASC, a.id ASC LIMIT ?"
                : " ORDER BY a.updated_at DESC, a.id DESC LIMIT ?";
        String cursor = after == null ? "" : direction == CursorPageRequest.Direction.ASC
                ? " AND (a.updated_at, a.id) > (?, ?)" : " AND (a.updated_at, a.id) < (?, ?)";
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.name, a.worker_type, a.phase, a.runtime, a.capabilities::text, a.metadata::text,
                       a.created_at, a.updated_at, a.version, template.template_name
                 FROM agents a JOIN resource_scopes s ON s.resource_type = 'WORKER' AND s.resource_id = a.id
                 LEFT JOIN LATERAL (
                      SELECT COALESCE(t.display_name, t.name) AS template_name
                        FROM worker_template_instances i
                        JOIN worker_templates t ON t.id = i.template_id
                       WHERE i.worker_id = a.id AND i.status = 'SUCCEEDED'
                       ORDER BY i.updated_at DESC, i.id DESC
                       LIMIT 1
                 ) template ON TRUE
                 WHERE s.tenant_id = ? AND s.project_id = ? AND s.team = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                JOIN projects p ON p.id = m.project_id AND p.tenant_id = m.tenant_id
                                WHERE m.tenant_id = s.tenant_id
                                  AND (m.project_id::text = s.project_id OR p.name = s.project_id)
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """);
        java.util.List<Object> values = new java.util.ArrayList<>(java.util.List.of(principal.scope().tenant(),
                principal.scope().project(), principal.scope().team(), principal.subject()));
        if (status != null && !status.isBlank()) { sql.append(" AND a.phase = ?"); values.add(status.trim()); }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (a.name ILIKE ? OR a.runtime ILIKE ?)");
            values.add("%" + query.trim() + "%"); values.add("%" + query.trim() + "%");
        }
        sql.append(cursor).append(order);
        if (after != null) { values.add(JdbcSupport.timestamp(after.updatedAt())); values.add(after.id()); }
        values.add(limit);
        return jdbc.query(sql.toString(), this::mapWithTemplate, values.toArray());
    }

    public Optional<AgentRecord> findReadyMatching(String taskSpecJson, Instant now) {
        return jdbc.query("""
                SELECT id, name, worker_type, phase, runtime, capabilities::text, metadata::text,
                       created_at, updated_at, version
                 FROM agents
                 WHERE phase = 'READY'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM worker_operations operation
                        WHERE operation.agent_id = agents.id
                          AND operation.status IN ('PENDING', 'RUNNING')
                          AND operation.lease_expires_at > ?
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(
                              CASE
                                  WHEN jsonb_typeof(CAST(? AS jsonb)->'requiredCapabilities') = 'array'
                                  THEN CAST(? AS jsonb)->'requiredCapabilities'
                                  ELSE '[]'::jsonb
                              END) AS required(capability)
                        WHERE NOT jsonb_exists(agents.capabilities, required.capability)
                   )
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, this::map, JdbcSupport.timestamp(now), JdbcSupport.json(taskSpecJson),
                JdbcSupport.json(taskSpecJson))
                .stream().findFirst();
    }

    /** Matches an unassigned task only against agents in the same tenant/project. */
    public Optional<AgentRecord> findReadyMatchingInTaskProject(String taskSpecJson, UUID taskId, Instant now) {
        return jdbc.query("""
                SELECT a.id, a.name, a.worker_type, a.phase, a.runtime, a.capabilities::text, a.metadata::text,
                       a.created_at, a.updated_at, a.version
                  FROM agents a
                  JOIN resource_scopes worker_scope
                    ON worker_scope.resource_type = 'WORKER' AND worker_scope.resource_id = a.id
                  JOIN resource_scopes task_scope
                    ON task_scope.resource_type = 'TASK' AND task_scope.resource_id = ?
                   AND task_scope.tenant_id = worker_scope.tenant_id
                   AND task_scope.project_id = worker_scope.project_id
                 WHERE a.phase = 'READY'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM worker_operations operation
                        WHERE operation.agent_id = a.id
                          AND operation.status IN ('PENDING', 'RUNNING')
                          AND operation.lease_expires_at > ?
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(
                              CASE
                                  WHEN jsonb_typeof(CAST(? AS jsonb)->'requiredCapabilities') = 'array'
                                  THEN CAST(? AS jsonb)->'requiredCapabilities'
                                  ELSE '[]'::jsonb
                              END) AS required(capability)
                        WHERE NOT jsonb_exists(a.capabilities, required.capability)
                   )
                 ORDER BY a.id
                 LIMIT 1
                 FOR UPDATE OF a SKIP LOCKED
                """, this::map, taskId, JdbcSupport.timestamp(now), JdbcSupport.json(taskSpecJson),
                JdbcSupport.json(taskSpecJson)).stream().findFirst();
    }

    public Optional<AgentRecord> findReadyMatchingForTeam(String taskSpecJson, UUID teamId, Instant now) {
        return jdbc.query("""
                SELECT agents.id, agents.name, agents.worker_type, agents.phase, agents.runtime,
                       agents.capabilities::text, agents.metadata::text,
                       agents.created_at, agents.updated_at, agents.version
                  FROM agents
                  JOIN team_memberships membership ON membership.agent_id = agents.id
                 WHERE membership.team_id = ? AND membership.status = 'ACTIVE'
                   AND agents.phase = 'READY'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM worker_operations operation
                        WHERE operation.agent_id = agents.id
                          AND operation.status IN ('PENDING', 'RUNNING')
                          AND operation.lease_expires_at > ?
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(
                              CASE
                                  WHEN jsonb_typeof(CAST(? AS jsonb)->'requiredCapabilities') = 'array'
                                  THEN CAST(? AS jsonb)->'requiredCapabilities'
                                  ELSE '[]'::jsonb
                              END) AS required(capability)
                        WHERE NOT jsonb_exists(agents.capabilities, required.capability)
                   )
                 ORDER BY agents.id
                 LIMIT 1
                 FOR UPDATE OF agents SKIP LOCKED
                """, this::map, teamId, JdbcSupport.timestamp(now), JdbcSupport.json(taskSpecJson),
                JdbcSupport.json(taskSpecJson))
                .stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM agents", Long.class);
        return count == null ? 0 : count;
    }

    public AgentRecord updatePhase(UUID id, AgentPhase phase, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET phase = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("agent", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM agents WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private AgentRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AgentRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                io.agentteams.domain.agent.WorkerType.valueOf(rs.getString("worker_type")),
                AgentPhase.valueOf(rs.getString("phase")), rs.getString("runtime"),
                rs.getString("capabilities"), rs.getString("metadata"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private AgentRecord mapWithTemplate(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AgentRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                io.agentteams.domain.agent.WorkerType.valueOf(rs.getString("worker_type")),
                AgentPhase.valueOf(rs.getString("phase")), rs.getString("runtime"),
                rs.getString("capabilities"), rs.getString("metadata"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("template_name"));
    }
}

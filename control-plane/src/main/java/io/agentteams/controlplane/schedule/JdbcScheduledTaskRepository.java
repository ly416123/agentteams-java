package io.agentteams.controlplane.schedule;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL repository for durable schedule definitions and atomic state changes. */
public class JdbcScheduledTaskRepository implements ScheduledTaskRepository {
    private final JdbcTemplate jdbc;

    public JdbcScheduledTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public ScheduledTaskDefinition insert(ScheduledTaskDefinition definition) {
        try {
            jdbc.update("""
                    INSERT INTO scheduled_tasks
                        (id, name, organization_id, tenant_id, project_id, cron_expression, time_zone,
                         title, description, spec, actor, source, enabled, next_run_at, last_run_at,
                         last_task_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.id(), definition.name(), definition.scope().organizationId(),
                    definition.scope().tenantId(), definition.scope().projectId(), definition.cronExpression(),
                    definition.timeZone(), definition.title(), definition.description(), JdbcSupport.json(definition.specJson()),
                    definition.actor(), definition.source(), definition.enabled(), JdbcSupport.timestamp(definition.nextRunAt()),
                    timestamp(definition.lastRunAt()), definition.lastTaskId(), JdbcSupport.timestamp(definition.createdAt()),
                    JdbcSupport.timestamp(definition.updatedAt()), definition.version());
            return definition;
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("schedule name already exists in tenant", error);
        }
    }

    @Override
    public Optional<ScheduledTaskDefinition> find(ScheduledTaskScope scope, UUID id) {
        return query("""
                SELECT id, name, organization_id, tenant_id, project_id, cron_expression, time_zone,
                       title, description, spec::text, actor, source, enabled, next_run_at, last_run_at,
                       last_task_id, created_at, updated_at, version
                  FROM scheduled_tasks
                 WHERE organization_id = ? AND tenant_id = ? AND (project_id = ? OR (project_id IS NULL AND ? IS NULL))
                   AND id = ?
                """, scope, id).stream().findFirst();
    }

    @Override
    public List<ScheduledTaskDefinition> list(ScheduledTaskScope scope) {
        return query("""
                SELECT id, name, organization_id, tenant_id, project_id, cron_expression, time_zone,
                       title, description, spec::text, actor, source, enabled, next_run_at, last_run_at,
                       last_task_id, created_at, updated_at, version
                  FROM scheduled_tasks
                 WHERE organization_id = ? AND tenant_id = ? AND (project_id = ? OR (project_id IS NULL AND ? IS NULL))
                 ORDER BY name, id
                """, scope, null);
    }

    @Override
    public List<ScheduledTaskDefinition> findDue(Instant now, int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                SELECT id, name, organization_id, tenant_id, project_id, cron_expression, time_zone,
                       title, description, spec::text, actor, source, enabled, next_run_at, last_run_at,
                       last_task_id, created_at, updated_at, version
                  FROM scheduled_tasks
                 WHERE enabled = TRUE AND next_run_at <= ?
                 ORDER BY next_run_at, id
                 LIMIT ?
                """, this::map, JdbcSupport.timestamp(now), limit);
    }

    @Override
    @Transactional
    public ScheduledTaskDefinition transition(ScheduledTaskScope scope, UUID id, boolean expectedEnabled,
            boolean nextEnabled, String operationKey, Instant now) {
        if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey is required");
        int updated = jdbc.update("""
                UPDATE scheduled_tasks
                   SET enabled = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND organization_id = ? AND tenant_id = ?
                   AND (project_id = ? OR (project_id IS NULL AND ? IS NULL))
                   AND enabled = ?
                """, nextEnabled, JdbcSupport.timestamp(now), id, scope.organizationId(), scope.tenantId(),
                scope.projectId(), scope.projectId(), expectedEnabled);
        if (updated == 0) {
            ScheduledTaskDefinition current = find(scope, id).orElseThrow(ScheduledTaskNotFoundException::new);
            if (current.enabled() == nextEnabled) return current;
            throw new IllegalStateException("schedule state changed concurrently");
        }
        return find(scope, id).orElseThrow(ScheduledTaskNotFoundException::new);
    }

    @Override
    @Transactional
    public ScheduledTaskDefinition resume(ScheduledTaskScope scope, UUID id, String operationKey,
            Instant nextRunAt, Instant now) {
        if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey is required");
        int updated = jdbc.update("""
                UPDATE scheduled_tasks SET enabled = TRUE, next_run_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND organization_id = ? AND tenant_id = ?
                   AND (project_id = ? OR (project_id IS NULL AND ? IS NULL))
                   AND enabled = FALSE
                """, JdbcSupport.timestamp(nextRunAt), JdbcSupport.timestamp(now), id,
                scope.organizationId(), scope.tenantId(), scope.projectId(), scope.projectId());
        if (updated == 0 && find(scope, id).map(ScheduledTaskDefinition::enabled).orElse(false)) {
            return find(scope, id).orElseThrow(ScheduledTaskNotFoundException::new);
        }
        if (updated == 0) throw new ScheduledTaskNotFoundException();
        return jdbc.query("""
                SELECT id, name, organization_id, tenant_id, project_id, cron_expression, time_zone,
                       title, description, spec::text, actor, source, enabled, next_run_at, last_run_at,
                       last_task_id, created_at, updated_at, version
                  FROM scheduled_tasks WHERE id = ?
                """, this::map, id).stream().findFirst().orElseThrow(ScheduledTaskNotFoundException::new);
    }

    @Override
    @Transactional
    public boolean advance(UUID id, Instant dueAt, UUID taskId, Instant nextRunAt, Instant now) {
        return jdbc.update("""
                UPDATE scheduled_tasks
                   SET last_run_at = ?, last_task_id = ?, next_run_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND enabled = TRUE AND next_run_at <= ?
                """, JdbcSupport.timestamp(dueAt), taskId, JdbcSupport.timestamp(nextRunAt),
                JdbcSupport.timestamp(now), id, JdbcSupport.timestamp(dueAt)) == 1;
    }

    private List<ScheduledTaskDefinition> query(String sql, ScheduledTaskScope scope, UUID id) {
        Object[] args = id == null
                ? new Object[] {scope.organizationId(), scope.tenantId(), scope.projectId(), scope.projectId()}
                : new Object[] {scope.organizationId(), scope.tenantId(), scope.projectId(), scope.projectId(), id};
        return jdbc.query(sql, this::map, args);
    }

    private ScheduledTaskDefinition map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp lastRun = rs.getTimestamp("last_run_at");
        return new ScheduledTaskDefinition(rs.getObject("id", UUID.class), rs.getString("name"),
                new ScheduledTaskScope(rs.getString("organization_id"), rs.getString("tenant_id"), rs.getString("project_id")),
                rs.getString("cron_expression"), rs.getString("time_zone"), rs.getString("title"),
                rs.getString("description"), rs.getString("spec"), rs.getString("actor"), rs.getString("source"),
                rs.getBoolean("enabled"), JdbcSupport.instant(rs, "next_run_at"),
                lastRun == null ? null : lastRun.toInstant(), rs.getObject("last_task_id", UUID.class),
                rs.getLong("version"), JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"));
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }
}

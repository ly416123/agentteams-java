package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregates project resources from their durable scope bindings for dashboard cards. */
@Service
public final class DashboardResourcesService {
    static final String ACTIVE_MEMBERSHIP_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM project_memberships m
                  JOIN projects p ON p.tenant_id = m.tenant_id AND p.id = m.project_id
                 WHERE m.tenant_id = ?
                   AND (p.name = ? OR p.id::text = ?)
                   AND m.subject = ?
                   AND m.status = 'ACTIVE'
            )
            """;

    private static final String SQL = """
            WITH visible_scopes AS (
                SELECT resource_type, resource_id
                  FROM resource_scopes s
                 WHERE s.tenant_id = ? AND s.project_id = ? AND s.team = ?
                   AND EXISTS (SELECT 1
                                FROM project_memberships m
                                JOIN projects p ON p.tenant_id = m.tenant_id AND p.id = m.project_id
                               WHERE m.tenant_id = s.tenant_id
                                 AND (p.name = s.project_id OR p.id::text = s.project_id)
                                  AND m.subject = ? AND m.status = 'ACTIVE')
            )
            SELECT
                (SELECT COUNT(*) FROM tasks t JOIN visible_scopes s
                   ON s.resource_type = 'TASK' AND s.resource_id = t.id) AS tasks_total,
                (SELECT COUNT(*) FROM tasks t JOIN visible_scopes s
                   ON s.resource_type = 'TASK' AND s.resource_id = t.id WHERE t.phase = 'QUEUED') AS tasks_queued,
                (SELECT COUNT(*) FROM tasks t JOIN visible_scopes s
                   ON s.resource_type = 'TASK' AND s.resource_id = t.id WHERE t.phase = 'RUNNING') AS tasks_running,
                (SELECT COUNT(*) FROM tasks t JOIN visible_scopes s
                   ON s.resource_type = 'TASK' AND s.resource_id = t.id WHERE t.phase = 'SUCCEEDED') AS tasks_succeeded,
                (SELECT COUNT(*) FROM tasks t JOIN visible_scopes s
                   ON s.resource_type = 'TASK' AND s.resource_id = t.id WHERE t.phase = 'FAILED') AS tasks_failed,
                (SELECT COUNT(*) FROM agents a JOIN visible_scopes s
                   ON s.resource_type = 'WORKER' AND s.resource_id = a.id WHERE a.phase = 'READY') AS workers_ready,
                (SELECT COUNT(*) FROM agents a JOIN visible_scopes s
                   ON s.resource_type = 'WORKER' AND s.resource_id = a.id WHERE a.phase = 'PROVISIONING') AS workers_connecting,
                (SELECT COUNT(*) FROM agents a JOIN visible_scopes s
                   ON s.resource_type = 'WORKER' AND s.resource_id = a.id WHERE a.phase IN ('FAILED', 'OFFLINE')) AS workers_unhealthy,
                (SELECT COUNT(*) FROM agents a JOIN visible_scopes s
                   ON s.resource_type = 'WORKER' AND s.resource_id = a.id WHERE a.phase = 'DRAINING') AS workers_draining,
                (SELECT COUNT(*) FROM teams t JOIN visible_scopes s
                   ON s.resource_type = 'TEAM' AND s.resource_id = t.id) AS teams_total,
                (SELECT COUNT(*) FROM teams t JOIN visible_scopes s
                   ON s.resource_type = 'TEAM' AND s.resource_id = t.id WHERE t.status = 'ACTIVE') AS teams_active
            """;

    private final JdbcTemplate jdbc;

    @Autowired
    public DashboardResourcesService(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    DashboardResourcesService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Resources summarize() {
        Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        boolean activeMembership = Boolean.TRUE.equals(jdbc.queryForObject(ACTIVE_MEMBERSHIP_SQL, Boolean.class,
                principal.scope().tenant(), principal.scope().project(), principal.scope().project(),
                principal.subject()));
        if (!activeMembership) {
            throw new AuthorizationException("active project membership required");
        }
        Resources result = jdbc.queryForObject(SQL, (rs, row) -> new Resources(
                new TaskCounts(rs.getLong("tasks_total"), rs.getLong("tasks_queued"),
                        rs.getLong("tasks_running"), rs.getLong("tasks_succeeded"), rs.getLong("tasks_failed")),
                new WorkerCounts(rs.getLong("workers_ready"), rs.getLong("workers_connecting"),
                        rs.getLong("workers_unhealthy"), rs.getLong("workers_draining")),
                new TeamCounts(rs.getLong("teams_total"), rs.getLong("teams_active"))),
                principal.scope().tenant(), principal.scope().project(), principal.scope().team(),
                principal.subject());
        if (result == null) throw new IllegalStateException("dashboard resource query returned no row");
        return result;
    }

    public record Resources(TaskCounts tasks, WorkerCounts workers, TeamCounts teams) { }

    public record TaskCounts(long total, long queued, long running, long succeeded, long failed) { }

    public record WorkerCounts(long ready, long connecting, long unhealthy, long draining) { }

    public record TeamCounts(long total, long active) { }
}

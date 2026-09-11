package io.agentteams.controlplane.persistence;

import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TaskRepository {

    /** 单一列清单：所有行级 SELECT 共用，G03 新列只在此处追加（消除列清单漂移）。 */
    private static final String TASK_COLUMNS = """
            id, title, description, phase, priority, spec::text, actor, source,
            failure_code, redacted_failure_message, created_at, updated_at, version, task_type,
            archive_status, archived_at, archive_actor, parent_task_id, kind
            """;

    private final JdbcTemplate jdbc;

    TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskRecord task) {
        jdbc.update("""
                INSERT INTO tasks
                    (id, title, description, phase, priority, spec, actor, source,
                     failure_code, redacted_failure_message, created_at, updated_at, version, task_type,
                     archive_status, archived_at, archive_actor, parent_task_id, kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.title(), task.description(), task.phase().name(), task.priority(),
                JdbcSupport.json(task.specJson()), task.actor(), task.source(), task.failureCode(),
                JdbcSupport.failureMessage(task.redactedFailureMessage()), JdbcSupport.timestamp(task.createdAt()),
                JdbcSupport.timestamp(task.updatedAt()), task.version(), task.taskType(),
                task.archiveStatus(), task.archivedAt() == null ? null : JdbcSupport.timestamp(task.archivedAt()),
                task.archiveActor(), task.parentTaskId(), task.kind());
    }

    public Optional<TaskRecord> findById(UUID id) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    public Optional<TaskRecord> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public List<TaskListRecord> findPage(Principal principal, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction, TaskPhase phase, java.util.Collection<TaskPhase> statuses,
            UUID teamId, UUID workerId, String actor, Instant from, Instant to, String query,
            String archiveStatus) {
        String order = direction == CursorPageRequest.Direction.ASC
                ? " ORDER BY t.updated_at ASC, t.id ASC LIMIT ?"
                : " ORDER BY t.updated_at DESC, t.id DESC LIMIT ?";
        String cursor = after == null ? "" : direction == CursorPageRequest.Direction.ASC
                ? " AND (t.updated_at, t.id) > (?, ?)" : " AND (t.updated_at, t.id) < (?, ?)";
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.title, t.phase, t.priority, t.actor, t.source, t.task_type,
                       t.created_at, t.updated_at, t.version, t.archive_status,
                       s.tenant_id, s.project_id, s.team,
                       team_ref.team_id, worker_ref.agent_id
                  FROM tasks t JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = t.id
                  JOIN projects scoped_project ON scoped_project.tenant_id = s.tenant_id
                                             AND (scoped_project.id::text = s.project_id
                                                  OR scoped_project.name = s.project_id)
                  LEFT JOIN LATERAL (SELECT tt.team_id FROM team_tasks tt
                                      WHERE tt.task_id = t.id ORDER BY tt.created_at, tt.team_id LIMIT 1) team_ref
                    ON TRUE
                  LEFT JOIN LATERAL (SELECT ta.agent_id FROM task_assignments ta
                                      WHERE ta.task_id = t.id ORDER BY ta.created_at, ta.id LIMIT 1) worker_ref
                    ON TRUE
                 WHERE s.tenant_id = ?
                   AND (scoped_project.id::text = ? OR scoped_project.name = ?) AND s.team = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = scoped_project.tenant_id
                                  AND m.project_id = scoped_project.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """);
        // principal scope.project 兼容项目名与 id 两种形态（kind token claims 用项目名）
        List<Object> args = new java.util.ArrayList<>(List.of(principal.scope().tenant(), principal.scope().project(),
                principal.scope().project(), principal.scope().team(), principal.subject()));
        if (phase != null) { sql.append(" AND t.phase = ?"); args.add(phase.name()); }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND t.phase IN (")
                    .append(String.join(",", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(')');
            statuses.forEach(item -> args.add(item.name()));
        }
        if (archiveStatus != null && !"ALL".equalsIgnoreCase(archiveStatus)) {
            sql.append(" AND t.archive_status = ?");
            args.add(archiveStatus.toUpperCase(java.util.Locale.ROOT));
        }
        if (teamId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM team_tasks tt WHERE tt.task_id = t.id AND tt.team_id = ?)");
            args.add(teamId);
        }
        if (workerId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM task_assignments ta WHERE ta.task_id = t.id AND ta.agent_id = ?)");
            args.add(workerId);
        }
        if (actor != null && !actor.isBlank()) { sql.append(" AND t.actor = ?"); args.add(actor); }
        if (from != null) { sql.append(" AND t.updated_at >= ?"); args.add(JdbcSupport.timestamp(from)); }
        if (to != null) { sql.append(" AND t.updated_at < ?"); args.add(JdbcSupport.timestamp(to)); }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (t.title ILIKE ? OR t.description ILIKE ?)");
            String pattern = "%" + query.trim() + "%";
            args.add(pattern); args.add(pattern);
        }
        sql.append(cursor).append(order);
        if (after != null) { args.add(JdbcSupport.timestamp(after.updatedAt())); args.add(after.id()); }
        args.add(limit);
        return jdbc.query(sql.toString(), this::mapListItem, args.toArray());
    }

    public List<UUID> findIdsByPhase(TaskPhase phase, int limit) {
        return findIdsByPhase(phase, limit, null);
    }

    public List<UUID> findIdsByPhase(TaskPhase phase, int limit, Instant now) {
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        String recoveryFilter = now == null ? "" : """
                AND NOT EXISTS (SELECT 1 FROM task_recovery_states recovery
                                 WHERE recovery.task_id = tasks.id
                                   AND recovery.status = 'READY'
                                   AND recovery.next_attempt_at > ?)
                """;
        if (now == null) {
            return jdbc.query("""
                SELECT id FROM tasks
                 WHERE phase = ?
                 ORDER BY priority DESC, created_at ASC, id ASC
                 LIMIT ?
                """, (rs, row) -> rs.getObject("id", UUID.class), phase.name(), limit);
        }
        return jdbc.query("""
                SELECT tasks.id FROM tasks
                 WHERE tasks.phase = ?
                """ + recoveryFilter + """
                 ORDER BY tasks.priority DESC, tasks.created_at ASC, tasks.id ASC
                 LIMIT ?
                """, (rs, row) -> rs.getObject("id", UUID.class), phase.name(), JdbcSupport.timestamp(now), limit);
    }

    public TaskRecord updateState(TaskRecord next, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET phase = ?, spec = ?, failure_code = ?, redacted_failure_message = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, next.phase().name(), JdbcSupport.json(next.specJson()), next.failureCode(),
                JdbcSupport.failureMessage(next.redactedFailureMessage()),
                JdbcSupport.timestamp(next.updatedAt()), next.id(), expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", next.id(), expectedVersion, actualVersion(next.id()));
        }
        return findById(next.id()).orElseThrow();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class);
        return count == null ? 0 : count;
    }

    public TaskRecord updatePhase(UUID id, TaskPhase phase, long expectedVersion, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET phase = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, phase.name(), JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    /** 任务聚合事件游标约定（G02）：每次 append 任务聚合事件前版本递增，返回递增后的版本。
     *  调用方须已持有任务行锁（findByIdForUpdate）——与 updatePhase/updateArchive 等的递增语义一致。 */
    public long incrementVersion(UUID id) {
        Long version = jdbc.queryForObject(
                "UPDATE tasks SET version = version + 1, updated_at = now() WHERE id = ? RETURNING version",
                Long.class, id);
        if (version == null) {
            throw new IllegalStateException("task version increment returned no value: " + id);
        }
        return version;
    }

    /** 归档/取消归档（D6）：独立属性更新，不改 phase；updatedAt 为动作时刻（unarchive 时 archivedAt 为 null）。 */
    public TaskRecord updateArchive(UUID id, String archiveStatus, java.time.Instant archivedAt,
            String archiveActor, long expectedVersion, java.time.Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET archive_status = ?, archived_at = ?, archive_actor = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, archiveStatus, archivedAt == null ? null : JdbcSupport.timestamp(archivedAt),
                archiveActor, JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    /** 元数据更新（D9）：白名单字段与 spec 顶层键合并结果，由 Service 层算好后传入。 */
    public TaskRecord updateMetadata(UUID id, String title, String description, int priority,
            String specJson, long expectedVersion, java.time.Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE tasks
                   SET title = ?, description = ?, priority = ?, spec = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, title, description, priority, JdbcSupport.json(specJson),
                JdbcSupport.timestamp(updatedAt), id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockFailure("task", id, expectedVersion, actualVersion(id));
        }
        return findById(id).orElseThrow();
    }

    /** 受限删除（G02 D10）：仅 DRAFT 且无执行记录；由 Service 层准入后调用。 */
    public int delete(UUID id) {
        return jdbc.update("DELETE FROM tasks WHERE id = ?", id);
    }

    /** 删除任务的独立资源归属行（tasks 无级联约束）。 */
    public int deleteResourceScope(UUID id) {
        return jdbc.update("DELETE FROM resource_scopes WHERE resource_type = 'TASK' AND resource_id = ?", id);
    }

    public java.util.Map<String, Long> countByPhase(Principal principal, String archiveStatus) {
        String filter = archiveStatus == null || "ALL".equalsIgnoreCase(archiveStatus) ? ""
                : " AND t.archive_status = ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(principal.scope().tenant());
        args.add(principal.scope().project());
        args.add(principal.scope().project());
        args.add(principal.scope().team());
        args.add(principal.subject());
        if (!filter.isEmpty()) {
            args.add(archiveStatus.toUpperCase(java.util.Locale.ROOT));
        }
        return jdbc.query("""
                SELECT t.phase, count(*) AS total
                  FROM tasks t JOIN resource_scopes s ON s.resource_type = 'TASK' AND s.resource_id = t.id
                  JOIN projects scoped_project ON scoped_project.tenant_id = s.tenant_id
                                             AND (scoped_project.id::text = s.project_id
                                                  OR scoped_project.name = s.project_id)
                 WHERE s.tenant_id = ?
                   AND (scoped_project.id::text = ? OR scoped_project.name = ?) AND s.team = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = scoped_project.tenant_id
                                  AND m.project_id = scoped_project.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')""" + filter + """
                 GROUP BY t.phase
                """, rs -> {
            java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("phase"), rs.getLong("total"));
            }
            return counts;
        }, args.toArray());
    }

    /** G03：按 parent 读取子任务（创建序稳定，供 gate 与级联取消）。 */
    public List<TaskRecord> findByParent(UUID parentId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM tasks WHERE parent_task_id = ? ORDER BY created_at, id",
                this::map, parentId);
    }

    /** G03 D3 硬约束：parent 下 phase != expected 的子任务数（无子任务恒 0）。 */
    public long countByParentNotPhase(UUID parentId, TaskPhase phase) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE parent_task_id = ? AND phase <> ?",
                Long.class, parentId, phase.name());
        return count == null ? 0 : count;
    }

    /** G03 gate：存在 DRAFT 子任务的 parent（调度器 tick 扫描入口）。 */
    public List<UUID> findParentIdsWithDraftChildren(int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT parent_task_id FROM tasks
                 WHERE parent_task_id IS NOT NULL AND kind = 'SUBTASK' AND phase = 'DRAFT'
                 LIMIT ?
                """, UUID.class, limit);
    }

    /** G03 汇总轮：MAIN 已 SUCCEEDED 且存在子任务且全部子任务 SUCCEEDED 的 parent。 */
    public List<UUID> findParentIdsAllChildrenSucceeded(int limit) {
        return jdbc.queryForList("""
                SELECT p.id FROM tasks p
                 WHERE p.kind = 'MAIN' AND p.phase = 'SUCCEEDED'
                   AND EXISTS (SELECT 1 FROM tasks c WHERE c.parent_task_id = p.id)
                   AND NOT EXISTS (SELECT 1 FROM tasks c WHERE c.parent_task_id = p.id AND c.phase <> 'SUCCEEDED')
                 LIMIT ?
                """, UUID.class, limit);
    }

    private long actualVersion(UUID id) {
        return jdbc.query("SELECT version FROM tasks WHERE id = ?", (rs, row) -> rs.getLong(1), id)
                .stream().findFirst().orElse(-1L);
    }

    private TaskRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp archivedAt = rs.getTimestamp("archived_at");
        return new TaskRecord(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("description"), TaskPhase.valueOf(rs.getString("phase")),
                rs.getInt("priority"), rs.getString("spec"), rs.getString("actor"),
                rs.getString("source"), rs.getString("failure_code"),
                rs.getString("redacted_failure_message"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"), rs.getString("task_type"),
                rs.getString("archive_status"),
                archivedAt == null ? null : archivedAt.toInstant(), rs.getString("archive_actor"),
                rs.getObject("parent_task_id", UUID.class), rs.getString("kind"));
    }

    private TaskListRecord mapListItem(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskListRecord(rs.getObject("id", UUID.class), rs.getString("title"),
                TaskPhase.valueOf(rs.getString("phase")), rs.getInt("priority"), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("team"), rs.getString("actor"), rs.getString("source"),
                rs.getObject("team_id", UUID.class), rs.getObject("agent_id", UUID.class),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"),
                rs.getString("task_type"), rs.getString("archive_status"));
    }
}

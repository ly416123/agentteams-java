package io.agentteams.controlplane.persistence;

public final class FoundationTransaction {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AgentRepository agents;
    private final TaskRepository tasks;
    private final TaskAttemptRepository taskAttempts;
    private final TaskAssignmentRepository taskAssignments;
    private final AgentLeaseRepository agentLeases;
    private final DomainEventRepository domainEvents;
    private final OutboxEventRepository outboxEvents;
    private final ArtifactRepository artifacts;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final TeamRepository teams;

    FoundationTransaction(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        agents = new AgentRepository(jdbc);
        tasks = new TaskRepository(jdbc);
        taskAttempts = new TaskAttemptRepository(jdbc);
        taskAssignments = new TaskAssignmentRepository(jdbc);
        agentLeases = new AgentLeaseRepository(jdbc);
        domainEvents = new DomainEventRepository(jdbc);
        outboxEvents = new OutboxEventRepository(jdbc);
        artifacts = new ArtifactRepository(jdbc);
        idempotencyKeys = new IdempotencyKeyRepository(jdbc);
        teams = new TeamRepository(jdbc);
    }

    public AgentRepository agents() {
        return agents;
    }

    public TaskRepository tasks() {
        return tasks;
    }

    public TaskAttemptRepository taskAttempts() {
        return taskAttempts;
    }

    public TaskAssignmentRepository taskAssignments() {
        return taskAssignments;
    }

    public AgentLeaseRepository agentLeases() {
        return agentLeases;
    }

    public DomainEventRepository domainEvents() {
        return domainEvents;
    }

    public OutboxEventRepository outboxEvents() {
        return outboxEvents;
    }

    public ArtifactRepository artifacts() {
        return artifacts;
    }

    public IdempotencyKeyRepository idempotencyKeys() {
        return idempotencyKeys;
    }

    public TeamRepository teams() {
        return teams;
    }

    public java.util.List<java.util.UUID> expiredActiveLeaseIds(java.time.Instant now) {
        return jdbc.query("""
                SELECT id
                  FROM agent_leases
                 WHERE status = 'ACTIVE' AND expires_at <= ?
                 ORDER BY expires_at, id
                 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getObject(1, java.util.UUID.class), JdbcSupport.timestamp(now));
    }

    public java.util.Optional<TaskAssignmentRecord> findAssignmentByAttemptId(java.util.UUID attemptId) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, agent_id, phase, assigned_at, accepted_at, released_at,
                       details::text, created_at, updated_at, version
                  FROM task_assignments WHERE attempt_id = ?
                  ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> {
                    java.sql.Timestamp accepted = rs.getTimestamp("accepted_at");
                    java.sql.Timestamp released = rs.getTimestamp("released_at");
                    return new TaskAssignmentRecord(rs.getObject("id", java.util.UUID.class),
                            rs.getObject("task_id", java.util.UUID.class),
                            rs.getObject("attempt_id", java.util.UUID.class),
                            rs.getObject("agent_id", java.util.UUID.class),
                            io.agentteams.domain.task.TaskPhase.valueOf(rs.getString("phase")),
                            JdbcSupport.instant(rs, "assigned_at"),
                            accepted == null ? null : accepted.toInstant(),
                            released == null ? null : released.toInstant(), rs.getString("details"),
                            JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                            rs.getLong("version"));
                }, attemptId).stream().findFirst();
    }

    public TaskAssignmentRecord releaseAssignment(java.util.UUID id, java.time.Instant releasedAt,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE task_assignments
                   SET phase = 'CANCELLED', released_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, JdbcSupport.timestamp(releasedAt), JdbcSupport.timestamp(releasedAt), id, expectedVersion);
        if (updated == 0) {
            long actual = jdbc.query("SELECT version FROM task_assignments WHERE id = ?",
                    (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(-1L);
            throw new OptimisticLockFailure("task_assignment", id, expectedVersion, actual);
        }
        return findAssignmentById(id).orElseThrow();
    }

    private java.util.Optional<TaskAssignmentRecord> findAssignmentById(java.util.UUID id) {
        return jdbc.query("""
                SELECT id, task_id, attempt_id, agent_id, phase, assigned_at, accepted_at, released_at,
                       details::text, created_at, updated_at, version
                  FROM task_assignments WHERE id = ?
                """, (rs, row) -> {
                    java.sql.Timestamp accepted = rs.getTimestamp("accepted_at");
                    java.sql.Timestamp released = rs.getTimestamp("released_at");
                    return new TaskAssignmentRecord(rs.getObject("id", java.util.UUID.class),
                            rs.getObject("task_id", java.util.UUID.class),
                            rs.getObject("attempt_id", java.util.UUID.class),
                            rs.getObject("agent_id", java.util.UUID.class),
                            io.agentteams.domain.task.TaskPhase.valueOf(rs.getString("phase")),
                            JdbcSupport.instant(rs, "assigned_at"),
                            accepted == null ? null : accepted.toInstant(),
                            released == null ? null : released.toInstant(), rs.getString("details"),
                            JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                            rs.getLong("version"));
                }, id).stream().findFirst();
    }
}

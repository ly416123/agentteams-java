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
    private final io.agentteams.controlplane.config.ConfigLifecycleRepository configLifecycle;
    private final ModelProviderRepository modelProviders;
    private final ModelRepository models;
    private final ModelPriceRepository modelPrices;
    private final TaskSandboxRepository taskSandboxes;
    private final io.agentteams.controlplane.worker.WorkerOperationRepository workerOperations;

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
        configLifecycle = new io.agentteams.controlplane.config.ConfigLifecycleRepository(jdbc);
        modelProviders = new ModelProviderRepository(jdbc);
        models = new ModelRepository(jdbc);
        modelPrices = new ModelPriceRepository(jdbc);
        taskSandboxes = new TaskSandboxRepository(jdbc);
        workerOperations = new io.agentteams.controlplane.worker.WorkerOperationRepository(jdbc);
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

    public io.agentteams.controlplane.config.ConfigLifecycleRepository configLifecycle() {
        return configLifecycle;
    }

    public ModelProviderRepository modelProviders() {
        return modelProviders;
    }

    public ModelRepository models() {
        return models;
    }

    public ModelPriceRepository modelPrices() {
        return modelPrices;
    }

    public TaskSandboxRepository taskSandboxes() {
        return taskSandboxes;
    }

    public io.agentteams.controlplane.worker.WorkerOperationRepository workerOperations() {
        return workerOperations;
    }

    /** Test and migration helper for resources whose ownership is stored separately from the domain row. */
    public void insertResourceScope(String resourceType, java.util.UUID resourceId, String tenantId,
            String projectId, String team, java.time.Instant at) {
        jdbc.update("""
                INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, resourceType, resourceId, tenantId, projectId, team,
                JdbcSupport.timestamp(at), JdbcSupport.timestamp(at));
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

    /**
     * Reclaims a single stuck assignment: expires the lease, releases the
     * assignment and sandbox, cancels the attempt, invalidates any Gateway
     * command still queued for it, and queues the task again (unless the task
     * already reached a terminal phase). Must run inside its own transaction:
     * any optimistic-lock failure rolls the whole attempt back so the lease
     * stays ACTIVE and a later recovery round can retry it.
     */
    public ReclaimOutcome reclaimAttempt(java.util.UUID leaseId, java.time.Instant at,
            String reason, String eventType, java.util.UUID eventId) {
        AgentLeaseRecord lease = agentLeases().findById(leaseId).orElse(null);
        if (lease == null || !"ACTIVE".equals(lease.status())) {
            return ReclaimOutcome.noop();
        }
        TaskAttemptRecord attempt = taskAttempts().findById(lease.taskAttemptId()).orElse(null);
        if (attempt == null || attempt.phase().terminal()) {
            return ReclaimOutcome.noop();
        }
        TaskRecord task = tasks().findById(attempt.taskId()).orElse(null);
        if (task == null) {
            return ReclaimOutcome.noop();
        }
        agentLeases().updateStatus(lease.id(), "EXPIRED", at, lease.version(), at);
        TaskAssignmentRecord assignment = findAssignmentByAttemptId(attempt.id()).orElse(null);
        if (assignment != null && assignment.releasedAt() == null) {
            releaseAssignment(assignment.id(), at, assignment.version());
        }
        taskSandboxes().findByAttemptId(attempt.id()).ifPresent(sandbox -> {
            if (sandbox.status() != io.agentteams.application.api.SandboxStatus.DESTROYED
                    && sandbox.status() != io.agentteams.application.api.SandboxStatus.FAILED
                    && sandbox.status() != io.agentteams.application.api.SandboxStatus.EXPIRED) {
                taskSandboxes().updateStatus(sandbox.id(), io.agentteams.application.api.SandboxStatus.EXPIRED,
                        at, null, null, reason, "sandbox reclaimed with task attempt", sandbox.version(), at);
                FoundationPersistenceService.appendEvent(this, "task_sandbox", sandbox.id(), "SandboxExpired",
                        "{\"attemptId\":\"" + attempt.id() + "\"}", at, sandbox.version() + 1);
            }
        });
        taskAttempts().updatePhase(attempt.id(), io.agentteams.domain.task.TaskPhase.CANCELLED, at, null, null,
                attempt.version(), at);
        cancelGatewayCommands(attempt.id(), at);
        if (!task.phase().terminal()) {
            TaskRecord queued = new TaskRecord(task.id(), task.title(), task.description(),
                    io.agentteams.domain.task.TaskPhase.QUEUED, task.priority(), task.specJson(),
                    task.actor(), task.source(), null, null, task.createdAt(), at, task.version() + 1);
            tasks().updateState(queued, task.version());
            FoundationPersistenceService.appendEvent(this, eventId, "task", task.id(), eventType,
                    reclaimPayload(task, attempt, lease, assignment, at, reason), at, queued.version());
        }
        return new ReclaimOutcome(true, attempt.id(), task.id());
    }

    /** Marks Gateway commands for an attempt as cancelled so replay skips them. */
    public int cancelGatewayCommands(java.util.UUID attemptId, java.time.Instant at) {
        java.util.Objects.requireNonNull(attemptId, "attemptId");
        java.util.Objects.requireNonNull(at, "at");
        return jdbc.update("""
                UPDATE gateway_commands
                   SET cancelled_at = ?
                 WHERE attempt_id = ? AND cancelled_at IS NULL
                """, JdbcSupport.timestamp(at), attemptId.toString());
    }

    /** Pending (uncancelled) Gateway commands for an attempt; used by tests and diagnostics. */
    public long pendingGatewayCommandCount(java.util.UUID attemptId) {
        java.util.Objects.requireNonNull(attemptId, "attemptId");
        Long pending = jdbc.queryForObject("""
                SELECT count(*) FROM gateway_commands
                 WHERE attempt_id = ? AND cancelled_at IS NULL
                """, Long.class, attemptId.toString());
        return pending == null ? 0 : pending;
    }

    /** Outcome of a {@link #reclaimAttempt}; carries identity for follow-up cleanup. */
    public record ReclaimOutcome(boolean reclaimed, java.util.UUID attemptId, java.util.UUID taskId) {
        public static ReclaimOutcome noop() {
            return new ReclaimOutcome(false, null, null);
        }
    }

    private static String reclaimPayload(TaskRecord task, TaskAttemptRecord attempt, AgentLeaseRecord lease,
            TaskAssignmentRecord assignment, java.time.Instant at, String reason) {
        return "{\"taskId\":\"" + task.id() + "\",\"attemptId\":\"" + attempt.id()
                + "\",\"assignmentId\":\"" + (assignment == null ? "" : assignment.id())
                + "\",\"leaseId\":\"" + lease.id() + "\",\"recoveredAt\":\"" + at
                + "\",\"reason\":\"" + reason + "\"}";
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

package io.agentteams.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.domain.task.TaskAttempt;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigLifecycleRepository;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ResourceApplyRecord;
import io.agentteams.controlplane.usage.JdbcUsageBudgetRepository;
import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.usage.UsageBudgetEvaluation;
import io.agentteams.controlplane.usage.UsageBudgetPolicy;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class FoundationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        persistence = new FoundationPersistenceService(dataSource);
    }

    @Test
    void writesProjectBudgetPolicyAndEvaluationAfterLatestMigration() {
        Instant now = Instant.parse("2026-08-28T08:00:00Z");
        UUID policyId = UUID.randomUUID();
        UsageBudgetPolicy policy = new UsageBudgetPolicy(policyId, "tenant-a", "project-a", "USD",
                Duration.ofDays(1), new BigDecimal("10"), new BigDecimal("20"), Duration.ofHours(1),
                UsageBudgetPolicy.Status.ACTIVE, now, now, 0);
        JdbcUsageBudgetRepository budgets = new JdbcUsageBudgetRepository(jdbc);

        assertThat(budgets.insert(policy)).isEqualTo(policy);
        assertThat(budgets.findById(policyId, "tenant-a", "project-a")).hasValueSatisfying(actual -> {
            assertThat(actual.id()).isEqualTo(policyId);
            assertThat(actual.tenantId()).isEqualTo("tenant-a");
            assertThat(actual.projectId()).isEqualTo("project-a");
            assertThat(actual.softThreshold()).isEqualByComparingTo("10");
            assertThat(actual.hardThreshold()).isEqualByComparingTo("20");
        });

        UsageBudgetEvaluation evaluation = new UsageBudgetEvaluation(UUID.randomUUID(), policyId,
                now.minus(Duration.ofDays(1)), now, new BigDecimal("6"), new BigDecimal("72"),
                UsageBudgetEvaluation.Status.HARD_LIMIT, now);
        assertThat(budgets.insertEvaluationIfAbsent(policy, evaluation, "migration-it-fingerprint")).isTrue();
        assertThat(budgets.insertEvaluationIfAbsent(policy, evaluation, "migration-it-fingerprint")).isFalse();
        assertThat(budgets.findEvaluations(policyId, "tenant-a", "project-a", 10)).singleElement()
                .satisfies(actual -> {
                    assertThat(actual.id()).isEqualTo(evaluation.id());
                    assertThat(actual.status()).isEqualTo(UsageBudgetEvaluation.Status.HARD_LIMIT);
                    assertThat(actual.actualCost()).isEqualByComparingTo("6");
                    assertThat(actual.forecastCost()).isEqualByComparingTo("72");
                });
    }

    @Test
    void aggregatesUsageDimensionCompletenessAgainstPostgres() {
        Instant now = Instant.now().minusSeconds(1);
        jdbc.update("""
                INSERT INTO model_call_audits
                    (id, provider, model, latency_millis, prompt_tokens, completion_tokens, request_hash,
                     outcome, occurred_at, tenant_id, project_id, cost_usd, cost_status, worker_id, task_id,
                     team_id, tool_id, quota_id, quota_dimension)
                VALUES (?, 'openai', 'gpt-4o', 10, 1, 2, repeat('a', 64), 'SUCCESS', ?,
                        'tenant-a', 'project-a', 0, 'UNPRICED', ?, ?, ?, ?, ?, ?),
                       (?, 'openai', 'gpt-4o', 10, 1, 2, repeat('b', 64), 'SUCCESS', ?,
                        'tenant-a', 'project-a', 0, 'UNPRICED', NULL, NULL, NULL, '', NULL, '')
                """, UUID.randomUUID(), Timestamp.from(now), "worker-a", "task-a", "team-a", "tool-a", "quota-a",
                "project", UUID.randomUUID(), Timestamp.from(now.plusMillis(1)));

        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        try {
            UsageQueryService.UsageCompleteness result = new UsageQueryService(jdbc.getDataSource())
                    .completeness(now.minusSeconds(60), now.plusSeconds(60));

            assertThat(result.totalCalls()).isEqualTo(2);
            assertThat(result.dimensions()).filteredOn(dimension -> dimension.name().equals("workerId"))
                    .singleElement().satisfies(dimension -> {
                        assertThat(dimension.present()).isEqualTo(1);
                        assertThat(dimension.missing()).isEqualTo(1);
                    });
            assertThat(result.dimensions()).filteredOn(dimension -> dimension.name().equals("quotaDimension"))
                    .singleElement().satisfies(dimension -> {
                        assertThat(dimension.present()).isEqualTo(1);
                        assertThat(dimension.missing()).isEqualTo(1);
                    });
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void writesAgentTaskAttemptDomainEventAndOutboxInOneTransaction() {
        // The service is initialized with the same database in the implementation.
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();

        AgentRecord agent = AgentRecord.create(agentId, "agent-1", AgentPhase.READY,
                "fake", "{}", now);
        TaskRecord task = TaskRecord.draft(taskId, "Persist this task", "description", "actor", "test", now);
        TaskAttemptRecord attempt = TaskAttemptRecord.fromDomain(new TaskAttempt(
                attemptId, taskId, leaseId, TaskPhase.ASSIGNED, now, now, now.plusSeconds(60), null,
                "scheduler", "test", null, null, 0));
        UUID assignmentId = UUID.randomUUID();
        UUID leaseIdForAssignment = leaseId;
        TaskAssignmentRecord assignment = new TaskAssignmentRecord(assignmentId, taskId, attemptId, agentId,
                TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
        AgentLeaseRecord lease = new AgentLeaseRecord(leaseIdForAssignment, agentId, attemptId, now,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0);

        UUID eventId = persistence.createFoundation(agent, task, attempt, assignment, lease, now);

        Optional<AgentRecord> persistedAgent = persistence.inTransaction(tx -> tx.agents().findById(agentId));
        Optional<TaskRecord> persistedTask = persistence.inTransaction(tx -> tx.tasks().findById(taskId));
        Optional<TaskAttemptRecord> persistedAttempt = persistence.inTransaction(
                tx -> tx.taskAttempts().findById(attemptId));
        Optional<DomainEventRecord> persistedDomainEvent = persistence.inTransaction(
                tx -> tx.domainEvents().findByEventId(eventId));
        Optional<OutboxEventRecord> persistedOutboxEvent = persistence.inTransaction(
                tx -> tx.outboxEvents().findByEventId(eventId));
        List<String> domainEventTypes = persistence.inTransaction(tx -> tx.domainEvents().eventTypes());
        List<String> outboxEventTypes = persistence.inTransaction(tx -> tx.outboxEvents().eventTypes());

        assertThat(persistedAgent).contains(agent);
        assertThat(persistedTask).contains(task);
        assertThat(persistedAttempt).contains(attempt);
        assertThat(persistedDomainEvent).isPresent();
        assertThat(persistedOutboxEvent).isPresent();
        assertThat(domainEventTypes)
                .containsExactly("AgentCreated", "AgentLeaseCreated", "TaskAssignmentCreated",
                        "TaskAttemptCreated", "TaskCreated");
        assertThat(outboxEventTypes)
                .containsExactly("AgentCreated", "AgentLeaseCreated", "TaskAssignmentCreated",
                        "TaskAttemptCreated", "TaskCreated");

        TaskAttemptRecord sanitizedAttempt = persistence.updateAttemptPhase(attemptId, TaskPhase.FAILED,
                now.plusSeconds(1), "RUNTIME", "token=attempt-secret password:attempt-password "
                        + "https://example.test/?api_key=url-secret", 0, now.plusSeconds(1));
        assertThat(sanitizedAttempt.redactedFailureMessage())
                .contains("[REDACTED]")
                .doesNotContain("attempt-secret", "attempt-password", "url-secret");
    }

    @Test
    void rejectsTheSecondConcurrentTaskVersionUpdateWithTypedFailure() throws Exception {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID taskId = UUID.randomUUID();
        TaskRecord task = TaskRecord.draft(taskId, "Concurrent task", "description", "actor", "test", now);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(task);
            return null;
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Object> update = () -> {
            ready.countDown();
            release.await(10, TimeUnit.SECONDS);
            return persistence.inTransaction(tx -> tx.tasks().updatePhase(
                    taskId, TaskPhase.QUEUED, 0, now.plusSeconds(1)));
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(update);
            Future<Object> second = executor.submit(update);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            Future<?>[] results = {first, second};
            long failures = 0;
            for (Future<?> result : results) {
                try {
                    result.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(OptimisticLockFailure.class);
                    OptimisticLockFailure conflict = (OptimisticLockFailure) error.getCause();
                    assertThat(conflict.expectedVersion()).isZero();
                    assertThat(conflict.actualVersion()).isEqualTo(1);
                    failures++;
                }
            }
            assertThat(failures).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resourceApplyResultsAreFencedToTheCurrentBindingRevision() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID oldSnapshotId = UUID.randomUUID();
        UUID newSnapshotId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(agentId, "resource-agent", AgentPhase.READY,
                "fake", "{}", now);
        ConfigSnapshot oldSnapshot = new ConfigSnapshot(oldSnapshotId, "resource-agent", 1, "{}",
                "old-sha", "test", now);
        ConfigSnapshot newSnapshot = new ConfigSnapshot(newSnapshotId, "resource-agent", 2, "{}",
                "new-sha", "test", now.plusSeconds(1));
        ConfigBindingRecord oldBinding = new ConfigBindingRecord(bindingId, "resource-agent", agentId,
                oldSnapshotId, now);
        ConfigBindingRecord newBinding = new ConfigBindingRecord(bindingId, "resource-agent", agentId,
                newSnapshotId, now.plusSeconds(1));

        persistence.inTransaction(tx -> {
            tx.agents().insert(agent);
            return null;
        });
        ConfigSnapshotRepository snapshots = new ConfigSnapshotRepository(jdbc);
        snapshots.insertIfAbsent(oldSnapshot);
        snapshots.insertIfAbsent(newSnapshot);
        ConfigLifecycleRepository lifecycle = new ConfigLifecycleRepository(jdbc);
        lifecycle.upsertBinding(oldBinding);

        ResourceApplyRecord oldResult = new ResourceApplyRecord(bindingId, oldSnapshotId, agentId, 1,
                "SKILL", resourceId.toString(), "1", "sha256:old", "", "APPLIED", "", now);
        ResourceApplyRecord newResult = new ResourceApplyRecord(bindingId, newSnapshotId, agentId, 2,
                "SKILL", resourceId.toString(), "2", "sha256:new", "", "APPLIED", "", now.plusSeconds(2));
        assertThat(lifecycle.recordResourceApply(oldResult)).isTrue();
        lifecycle.upsertBindingIfNewer(newBinding, 2);
        assertThat(lifecycle.recordResourceApply(oldResult)).isFalse();
        assertThat(lifecycle.recordResourceApply(newResult)).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_resource_apply_records", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM runtime_resource_apply_records WHERE config_version = 1",
                String.class)).isEqualTo("APPLIED");
    }

    @Test
    void returnsTheOriginalTaskForARepeatedIdempotencyKey() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        CreateTaskCommand command = CreateTaskCommand.of(
                "same-key", "Idempotent task", "description", "actor", "api", now);

        TaskRecord first = persistence.createTask(command);
        TaskRecord second = persistence.createTask(command);

        assertThat(second).isEqualTo(first);
        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        assertThat(taskCount).isEqualTo(1);
        assertThatThrownBy(() -> persistence.createTask(CreateTaskCommand.of(
                "same-key", "different task", "description", "actor", "api", now)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void persistsPricesWithTenantProjectIsolationAndEffectiveLookup() {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        ModelPriceRecord tenantA = new ModelPriceRecord(UUID.randomUUID(), "tenant-a", "project-a",
                "openai", "gpt-4o", "USD", new BigDecimal("2.5"), new BigDecimal("10"),
                now.minusSeconds(60), null, "ACTIVE", now, now, 0, "alice", "alice");
        ModelPriceRecord tenantB = new ModelPriceRecord(UUID.randomUUID(), "tenant-b", "project-a",
                "openai", "gpt-4o", "USD", new BigDecimal("99"), new BigDecimal("99"),
                now.minusSeconds(60), null, "ACTIVE", now, now, 0, "bob", "bob");

        assertThat(persistence.createModelPrice(tenantA, "same-key", "hash-a").id()).isEqualTo(tenantA.id());
        assertThat(persistence.createModelPrice(tenantB, "same-key", "hash-b").id()).isEqualTo(tenantB.id());
        assertThat(persistence.findModelPrices("tenant-a", "project-a"))
                .extracting(ModelPriceRecord::id).containsExactly(tenantA.id());
        assertThat(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", now)).hasValueSatisfying(price -> {
                    assertThat(price.id()).isEqualTo(tenantA.id());
                    assertThat(price.inputPricePerMillionTokens()).isEqualByComparingTo("2.5");
                    assertThat(price.outputPricePerMillionTokens()).isEqualByComparingTo("10");
                });
        assertThat(persistence.findEffectiveModelPrice("tenant-b", "project-a", "openai", "gpt-4o",
                "USD", now)).hasValueSatisfying(price -> {
                    assertThat(price.id()).isEqualTo(tenantB.id());
                    assertThat(price.inputPricePerMillionTokens()).isEqualByComparingTo("99");
                    assertThat(price.outputPricePerMillionTokens()).isEqualByComparingTo("99");
                });
    }

    @Test
    void persistsOneSandboxPerAttemptAndUpdatesProviderBindingOptimistically() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(agentId, "sandbox-agent", AgentPhase.READY,
                "fake", "{}", now);
        TaskRecord task = TaskRecord.draft(taskId, "Sandbox task", "description", "actor", "test", now);
        TaskAttemptRecord attempt = TaskAttemptRecord.fromDomain(new TaskAttempt(
                attemptId, taskId, leaseId, TaskPhase.ASSIGNED, now, now, now.plusSeconds(60), null,
                "scheduler", "test", null, null, 0));
        TaskAssignmentRecord assignment = new TaskAssignmentRecord(UUID.randomUUID(), taskId, attemptId, agentId,
                TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
        AgentLeaseRecord lease = new AgentLeaseRecord(leaseId, agentId, attemptId, now,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0);
        persistence.createFoundation(agent, task, attempt, assignment, lease, now);

        TaskSandboxRecord requested = new TaskSandboxRecord(UUID.randomUUID(), taskId, attemptId,
                "task-attempt:" + attemptId, null, SandboxProfile.ISOLATED, SandboxStatus.REQUESTED,
                "python", null, now, now.plusSeconds(300), null, null, null, null, null, now, now, 0);
        persistence.inTransaction(tx -> {
            tx.taskSandboxes().insert(requested);
            return null;
        });

        Optional<TaskSandboxRecord> byKey = persistence.inTransaction(tx -> tx.taskSandboxes()
                .findByIdempotencyKey(requested.idempotencyKey()));
        assertThat(byKey).contains(requested);
        TaskSandboxRecord bound = persistence.inTransaction(tx -> tx.taskSandboxes().updateProviderBinding(
                requested.id(), "fake-sandbox-1", "fake://sandbox/1", SandboxStatus.READY,
                now.plusSeconds(1), 0, now.plusSeconds(1)));
        assertThat(bound.providerSandboxId()).isEqualTo("fake-sandbox-1");
        Optional<TaskSandboxRecord> byAttempt = persistence.inTransaction(tx -> tx.taskSandboxes()
                .findByAttemptId(attemptId));
        assertThat(byAttempt).contains(bound);
    }

    @Test
    void effectivePriceLookupExcludesDraftRetiredAndNotYetEffectiveRows() {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        ModelPriceRecord draft = new ModelPriceRecord(UUID.randomUUID(), "tenant-a", "project-a",
                "openai", "draft-model", "USD", BigDecimal.ONE, BigDecimal.ONE,
                now.minusSeconds(60), null, "DRAFT", now, now, 0, "alice", "alice");
        ModelPriceRecord retired = new ModelPriceRecord(UUID.randomUUID(), "tenant-a", "project-a",
                "openai", "retired-model", "USD", BigDecimal.ONE, BigDecimal.ONE,
                now.minusSeconds(60), null, "RETIRED", now, now, 0, "alice", "alice");
        ModelPriceRecord future = new ModelPriceRecord(UUID.randomUUID(), "tenant-a", "project-a",
                "openai", "future-model", "USD", BigDecimal.ONE, BigDecimal.ONE,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0, "alice", "alice");

        persistence.createModelPrice(draft, "draft-key", "draft-hash");
        persistence.createModelPrice(retired, "retired-key", "retired-hash");
        persistence.createModelPrice(future, "future-key", "future-hash");

        assertThat(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "draft-model",
                "USD", now)).isEmpty();
        assertThat(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "retired-model",
                "USD", now)).isEmpty();
        assertThat(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "future-model",
                "USD", now)).isEmpty();
    }

    @Test
    void updatesApprovalSpecAndTeamLinkIdempotently() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID taskId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        TaskRecord task = new TaskRecord(taskId, "approval task", "description", TaskPhase.DRAFT, 0,
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}",
                "actor", "matrix", null, null, now, now, 0);
        TeamRecord team = TeamRecord.create(teamId, "approval-team", "Approval team", now);
        persistence.inTransaction(tx -> {
            tx.tasks().insert(task);
            tx.teams().insert(team);
            tx.teams().linkTask(teamId, taskId, "PENDING", now);
            return null;
        });

        String approvedSpec = "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"},\"approvalGranted\":true}";
        TaskRecord approved = persistence.transitionTaskWithSpec(taskId, TaskPhase.DRAFT, approvedSpec, 0,
                now.plusSeconds(1), "approve-once", "approval-hash", "APPROVE_TASK", "APPROVED");
        TaskRecord duplicate = persistence.transitionTaskWithSpec(taskId, TaskPhase.DRAFT, approvedSpec, 0,
                now.plusSeconds(2), "approve-once", "approval-hash", "APPROVE_TASK", "APPROVED");

        assertThat(approved.specJson()).contains("\"approvalGranted\": true");
        assertThat(approved.version()).isEqualTo(1);
        assertThat(duplicate).isEqualTo(approved);
        Optional<String> approvalStatus = persistence.inTransaction(
                tx -> tx.teams().findApprovalStatusByTaskId(taskId));
        assertThat(approvalStatus).contains("APPROVED");
    }

    @Test
    void rollsBackAllFoundationRowsAndEventsWhenTheTransactionFails() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AgentRecord agent = AgentRecord.create(agentId, "rollback-agent", AgentPhase.READY, "fake", "{}", now);
        TaskRecord task = TaskRecord.draft(taskId, "rollback-task", "description", "actor", "test", now);
        TaskAttemptRecord attempt = TaskAttemptRecord.fromDomain(new TaskAttempt(
                attemptId, taskId, leaseId, TaskPhase.ASSIGNED, now, now, now.plusSeconds(60), null,
                "scheduler", "test", null, null, 0));
        TaskAssignmentRecord assignment = new TaskAssignmentRecord(assignmentId, taskId, attemptId, agentId,
                TaskPhase.ASSIGNED, now, null, null, "{}", now, now, 0);
        AgentLeaseRecord lease = new AgentLeaseRecord(leaseId, agentId, attemptId, now,
                now.plusSeconds(60), null, "ACTIVE", now, now, 0);

        assertThatThrownBy(() -> persistence.inTransaction(tx -> insertAndFail(
                tx, agent, task, attempt, assignment, lease, eventId, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intentional rollback");

        long agentCount = persistence.inTransaction(tx -> tx.agents().count());
        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        long taskAttemptCount = persistence.inTransaction(tx -> tx.taskAttempts().count());
        long taskAssignmentCount = persistence.inTransaction(tx -> tx.taskAssignments().count());
        long agentLeaseCount = persistence.inTransaction(tx -> tx.agentLeases().count());
        long domainEventCount = persistence.inTransaction(tx -> tx.domainEvents().count());
        long outboxEventCount = persistence.inTransaction(tx -> tx.outboxEvents().count());

        assertThat(agentCount).isZero();
        assertThat(taskCount).isZero();
        assertThat(taskAttemptCount).isZero();
        assertThat(taskAssignmentCount).isZero();
        assertThat(agentLeaseCount).isZero();
        assertThat(domainEventCount).isZero();
        assertThat(outboxEventCount).isZero();
    }

    @Test
    void concurrentSameIdempotencyKeyCreatesOneTaskAndOneIdempotencyRecord() throws Exception {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        CreateTaskCommand command = CreateTaskCommand.of(
                "concurrent-key", "Concurrent idempotent task", "description", "actor", "api", now);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<TaskRecord> create = () -> {
            ready.countDown();
            release.await(10, TimeUnit.SECONDS);
            return persistence.createTask(command);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TaskRecord> first = executor.submit(create);
            Future<TaskRecord> second = executor.submit(create);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            TaskRecord firstTask = first.get(10, TimeUnit.SECONDS);
            TaskRecord secondTask = second.get(10, TimeUnit.SECONDS);
            assertThat(secondTask).isEqualTo(firstTask);
        } finally {
            executor.shutdownNow();
        }

        long taskCount = persistence.inTransaction(tx -> tx.tasks().count());
        long idempotencyKeyCount = persistence.inTransaction(tx -> tx.idempotencyKeys().count());
        assertThat(taskCount).isEqualTo(1);
        assertThat(idempotencyKeyCount).isEqualTo(1);
    }

    private static Void insertAndFail(FoundationTransaction tx, AgentRecord agent, TaskRecord task,
            TaskAttemptRecord attempt, TaskAssignmentRecord assignment, AgentLeaseRecord lease,
            UUID eventId, Instant now) {
        tx.agents().insert(agent);
        tx.tasks().insert(task);
        tx.taskAttempts().insert(attempt);
        tx.taskAssignments().insert(assignment);
        tx.agentLeases().insert(lease);
        DomainEventRecord event = DomainEventRecord.create(eventId, "task", task.id(), "TaskCreated",
                "{\"id\":\"" + task.id() + "\"}", now, task.version());
        tx.domainEvents().insert(event);
        tx.outboxEvents().insert(OutboxEventRecord.pending(eventId, "task", task.id(), "TaskCreated",
                "{\"id\":\"" + task.id() + "\"}", now));
        throw new IllegalStateException("intentional rollback");
    }
}

package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTaskStateConsistencyRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcTaskStateConsistencyRepository repository;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcTaskStateConsistencyRepository(jdbc);
    }

    @Test
    void repeatedFindingIsMergedAndResolved() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TaskStateConsistencyIssue issue = new TaskStateConsistencyIssue(taskId, runId, "org-1", "tenant-1",
                "TASK_RUN_STATUS_MISMATCH", "SUCCEEDED", "RUNNING", null,
                "task phase and run status differ", NOW);

        repository.upsertIssue(issue, NOW);
        repository.upsertIssue(issue, NOW.plusSeconds(10));

        assertThat(repository.findOpenIssues(10)).singleElement().satisfies(record -> {
            assertThat(record.taskId()).isEqualTo(taskId);
            assertThat(record.runId()).isEqualTo(runId);
            assertThat(record.occurrences()).isEqualTo(2);
            assertThat(record.status()).isEqualTo("OPEN");
            assertThat(record.firstSeenAt()).isEqualTo(NOW);
            assertThat(record.lastSeenAt()).isEqualTo(NOW.plusSeconds(10));
        });

        repository.resolveIssue(taskId, runId, issue.type(), NOW.plusSeconds(20));

        assertThat(repository.findOpenIssues(10)).isEmpty();
        String persisted = jdbc.queryForObject("SELECT status, occurrences, resolved_at FROM task_state_consistency_issues "
                + "WHERE task_id = ? AND run_id = ? AND issue_type = ?", (rs, row) -> {
                    return rs.getString("status") + ":" + rs.getInt("occurrences") + ":" + (rs.getTimestamp("resolved_at") != null);
                }, taskId, runId, issue.type());
        assertThat(persisted).isEqualTo("RESOLVED:2:true");
    }

    @Test
    void snapshotsContainCrossTableFactsForRecentRuns() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks(id, title, description, phase, priority, spec, actor, source,
                                  created_at, updated_at, version)
                VALUES (?, 'consistency-test', '', 'SUCCEEDED', 0, '{}'::jsonb, 'test', 'test', ?, ?, 0)
                """, taskId, JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));
        jdbc.update("""
                INSERT INTO task_runs(id, task_id, organization_id, tenant_id, status, started_at, completed_at,
                                      created_at, updated_at, version)
                VALUES (?, ?, 'org-1', 'tenant-1', 'SUCCEEDED', ?, ?, ?, ?, 0)
                """, runId, taskId, JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW.plusSeconds(1)),
                JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO task_result_manifests(id, task_id, run_id, status, summary, created_at, updated_at, version)
                VALUES (?, ?, ?, 'SUCCEEDED', 'done', ?, ?, 0)
                """, UUID.randomUUID(), taskId, runId, JdbcSupport.timestamp(NOW.plusSeconds(1)),
                JdbcSupport.timestamp(NOW.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO task_process_events(id, task_id, run_id, organization_id, tenant_id, sequence,
                                                event_type, visibility, occurred_at, correlation_id, payload, payload_ref)
                VALUES (?, ?, ?, 'org-1', 'tenant-1', 0, 'task.completed', 'REQUESTER', ?, 'corr-1', '{}', NULL)
                """, UUID.randomUUID(), taskId, runId, JdbcSupport.timestamp(NOW.plusSeconds(1)));

        assertThat(repository.findSnapshots(NOW.minusSeconds(60), 10)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.taskId()).isEqualTo(taskId);
            assertThat(snapshot.runId()).isEqualTo(runId);
            assertThat(snapshot.taskPhase()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.runStatus()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.manifestStatus()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.processEventCount()).isEqualTo(1);
            assertThat(snapshot.maxProcessSequence()).isEqualTo(0);
        });
    }
}

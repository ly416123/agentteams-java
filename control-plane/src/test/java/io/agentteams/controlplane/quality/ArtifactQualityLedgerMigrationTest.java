package io.agentteams.controlplane.quality;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
final class ArtifactQualityLedgerMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    @Test
    void migrationCreatesScopedEventTableAndDeduplicatesIdempotencyKeys() {
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('artifact_quality_events')", String.class));
        UUID id = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id, tenant_id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                projectId, "tenant", "quality-test", "migration-test");
        Object[] row = {id, "org", "tenant", projectId, UUID.randomUUID(), UUID.randomUUID(), 0,
                "FINAL_STATUS", "FAILED", "failure", "LIVE", "runtime", "HIGH", "same-key"};
        String insert = """
                INSERT INTO artifact_quality_events
                (id, organization_id, tenant_id, project_id, task_id, run_id, sequence, event_type,
                 status, summary, source_type, source_ref, confidence, idempotency_key, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (organization_id, tenant_id, project_id, idempotency_key) DO NOTHING
                """;
        jdbc.update(insert, row);
        jdbc.update(insert, UUID.randomUUID(), "org", "tenant", row[3], row[4], row[5], 1,
                "FINAL_STATUS", "FAILED", "failure", "LIVE", "runtime", "HIGH", "same-key");

        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), "org", "tenant", row[3], row[4], row[5], 2,
                "FINAL_STATUS", "FAILED", "x".repeat(501), "LIVE", "runtime", "HIGH", "too-long"));
        Integer count = jdbc.queryForObject("SELECT count(*) FROM artifact_quality_events WHERE idempotency_key='same-key'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}

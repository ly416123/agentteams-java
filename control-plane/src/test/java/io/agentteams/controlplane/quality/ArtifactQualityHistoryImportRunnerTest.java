package io.agentteams.controlplane.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
final class ArtifactQualityHistoryImportRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private ArtifactQualityHistoryImportRunner importer;

    @BeforeAll
    static void migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM artifact_quality_events");
        jdbc.update("DELETE FROM resource_scopes");
        jdbc.update("DELETE FROM task_runs");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM legacy_tenant_mappings");
        jdbc.update("DELETE FROM tenants");
        jdbc.update("DELETE FROM organizations");
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.parse("2026-10-13T00:00:00Z"));
        jdbc.update("INSERT INTO organizations(id, external_key, display_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                organizationId, "org-ext", "Org", now, now);
        jdbc.update("INSERT INTO tenants(id, organization_id, external_key, display_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                tenantId, organizationId, "tenant-ext", "Tenant", now, now);
        jdbc.update("INSERT INTO legacy_tenant_mappings(legacy_tenant_key, organization_id, tenant_id, created_at) VALUES (?, ?, ?, ?)",
                "tenant-alias", organizationId, tenantId, now);
        jdbc.update("INSERT INTO projects(id, tenant_id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                projectId, "tenant-ext", "project-a", "test", now, now);
        jdbc.update("INSERT INTO task_runs(id, task_id, organization_id, tenant_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, taskId, organizationId.toString(), tenantId.toString(), "SUCCEEDED", now, now);
        jdbc.update("INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "TASK", taskId, "tenant-ext", projectId.toString(), "team-a", now, now);
        importer = new ArtifactQualityHistoryImportRunner(jdbc, new JdbcArtifactQualityEventRepository(jdbc), "unused");
    }

    @Test
    void importsValidAndWeakHistoricalFactsIdempotentlyAndSkipsCorruptRows() throws Exception {
        String reportHash = "a".repeat(64);
        String valid = record(UUID.randomUUID(), taskId, runId, "history:sha256:" + reportHash + "#case:case-1:assertion:0",
                "history-valid");
        String weak = record(UUID.randomUUID(), null, null, "history:sha256:" + reportHash + "#case:case-2:final-status",
                "history-weak");
        Path input = Files.createTempFile("quality-history", ".jsonl");
        Files.writeString(input, valid + "\n{\n" + weak + "\n", java.nio.charset.StandardCharsets.UTF_8);

        ArtifactQualityHistoryImportRunner.ImportSummary first = importer.importFile(input);
        ArtifactQualityHistoryImportRunner.ImportSummary second = importer.importFile(input);

        assertEquals(2, first.imported());
        assertEquals(1, first.skipped());
        assertEquals(0, second.imported());
        assertEquals(2, second.duplicates());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM artifact_quality_events", Integer.class));
        assertNull(jdbc.queryForObject("SELECT task_id FROM artifact_quality_events WHERE idempotency_key='history-weak'", UUID.class));
    }

    @Test
    void skipsAValidRunThatIsScopedToAnotherProject() throws Exception {
        UUID otherProject = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.parse("2026-10-13T00:00:00Z"));
        jdbc.update("INSERT INTO projects(id, tenant_id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                otherProject, "tenant-ext", "project-b", "test", now, now);
        jdbc.update("UPDATE resource_scopes SET project_id=? WHERE resource_id=?", otherProject.toString(), taskId);
        Path input = Files.createTempFile("quality-history-cross-project", ".jsonl");
        Files.writeString(input, record(UUID.randomUUID(), taskId, runId,
                "history:sha256:" + "b".repeat(64) + "#case:case-1:assertion:0", "cross-project"),
                java.nio.charset.StandardCharsets.UTF_8);

        ArtifactQualityHistoryImportRunner.ImportSummary result = importer.importFile(input);

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM artifact_quality_events", Integer.class));
    }

    @Test
    void downgradesAnOrphanedHistoricalExecutionToProjectLevelFact() throws Exception {
        UUID historicalTask = UUID.randomUUID();
        UUID historicalRun = UUID.randomUUID();
        Path input = Files.createTempFile("quality-history-orphan", ".jsonl");
        Files.writeString(input, record(UUID.randomUUID(), historicalTask, historicalRun,
                "history:sha256:" + "c".repeat(64) + "#case:case-old:assertion:0", "orphan-history"),
                java.nio.charset.StandardCharsets.UTF_8);

        ArtifactQualityHistoryImportRunner.ImportSummary result = importer.importFile(input);

        assertEquals(1, result.imported());
        assertEquals(0, result.skipped());
        assertNull(jdbc.queryForObject("SELECT task_id FROM artifact_quality_events WHERE idempotency_key='orphan-history'", UUID.class));
        assertNull(jdbc.queryForObject("SELECT run_id FROM artifact_quality_events WHERE idempotency_key='orphan-history'", UUID.class));
        assertEquals("LOW", jdbc.queryForObject("SELECT confidence FROM artifact_quality_events WHERE idempotency_key='orphan-history'", String.class));
    }

    private String record(UUID id, UUID task, UUID run, String sourceRef, String key) throws Exception {
        var node = JSON.createObjectNode().put("id", id.toString()).put("sequence", 0).put("eventType", "QUALITY_CHECK")
                .put("status", "FAILED").put("summary", "Historical report records a failed artifact check.")
                .put("sourceType", "HISTORICAL").put("sourceRef", sourceRef).put("confidence", task == null ? "LOW" : "HIGH")
                .put("idempotencyKey", key).put("occurredAt", "2026-10-13T00:00:00Z");
        node.putObject("scope").put("tenant", "tenant-alias").put("project", "project-a");
        if (task == null) node.putNull("taskId"); else node.put("taskId", task.toString());
        if (run == null) node.putNull("runId"); else node.put("runId", run.toString());
        return JSON.writeValueAsString(node);
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}

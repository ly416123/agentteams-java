package io.agentteams.controlplane.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
final class JdbcArtifactQualityEventRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private final UUID projectId = UUID.randomUUID();
    private final ExecutionContext context = new ExecutionContext("org", "tenant", projectId.toString(), "team", "user");
    private final UUID taskId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private JdbcArtifactQualityEventRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM artifact_quality_events");
        jdbc.update("DELETE FROM projects");
        jdbc.update("INSERT INTO projects(id, tenant_id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                projectId, "tenant", "ledger", "test");
        repository = new JdbcArtifactQualityEventRepository(jdbc);
    }

    @Test
    void deduplicatesByStableKeyAndAppendsLateFactsInOccurredOrder() {
        ArtifactQualityEvent later = event("later", 2, Instant.parse("2026-10-13T02:00:00Z"));
        ArtifactQualityEvent lateArrival = event("late", 1, Instant.parse("2026-10-13T01:00:00Z"));

        assertTrue(repository.append(context, later));
        assertFalse(repository.append(context, later));
        assertTrue(repository.append(context, lateArrival));

        ArtifactQualityEventFilter filter = new ArtifactQualityEventFilter(null, taskId, null, null, null, null, null, 0, 20);
        var page = repository.find(context, filter);
        assertEquals(2, page.total());
        assertEquals("late", page.events().get(0).idempotencyKey());
        assertEquals("later", page.events().get(1).idempotencyKey());
    }

    @Test
    void queryNeverReturnsAnotherProjectEvents() {
        repository.append(context, event("own", 0, Instant.parse("2026-10-13T01:00:00Z")));
        UUID otherProject = UUID.randomUUID();
        jdbc.update("INSERT INTO projects(id, tenant_id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                otherProject, "tenant", "other", "test");
        ExecutionContext other = new ExecutionContext("org", "tenant", otherProject.toString(), "team", "user");

        assertEquals(0, repository.find(other, new ArtifactQualityEventFilter(null, null, null, null, null, null, null, 0, 20)).total());
    }

    @Test
    void supportsTheClassBasedTransactionProxyUsedByL5() {
        assertDoesNotThrow(() -> {
            ProxyFactory proxyFactory = new ProxyFactory(repository);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.getProxy();
        });
    }

    private ArtifactQualityEvent event(String key, long sequence, Instant at) {
        return new ArtifactQualityEvent(UUID.randomUUID(), "org", "tenant", projectId, taskId, runId, sequence,
                "QUALITY_CHECK", "report.pdf", null, null, null, "pdf_missing", "artifact_pdf", null, "FAILED",
                null, null, "PDF artifact missing", "LIVE", "runtime:evaluation:" + runId, "HIGH", key, at);
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource source = new org.postgresql.ds.PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}

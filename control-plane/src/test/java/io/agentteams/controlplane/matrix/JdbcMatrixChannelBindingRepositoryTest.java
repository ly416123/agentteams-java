package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.time.Instant;
import java.util.Set;
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
class JdbcMatrixChannelBindingRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    @BeforeAll
    static void migrate() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM matrix_outbox_messages");
        jdbc.update("DELETE FROM matrix_channel_bindings");
    }

    @Test
    void loadsBindingWithExactTenantScopeAndEventAllowlist() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO matrix_channel_bindings
                    (id, organization_id, tenant_id, project_id, room_id, event_types, enabled, version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, 0, ?, ?)
                """, id, "org-1", "tenant-1", "project-1", "!room:example.org",
                JdbcSupport.json(JdbcSupport.jsonArray(java.util.List.of("task.completed"))),
                JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));

        assertThat(new JdbcMatrixChannelBindingRepository(jdbc).findById(id)).contains(new MatrixChannelBinding(id,
                "org-1", "tenant-1", "project-1", "!room:example.org", Set.of("task.completed"), true));
    }

    @Test
    void callerOwnedOutboxIdIsIdempotent() {
        UUID id = UUID.randomUUID();
        MatrixOutboundRepository repository = new MatrixOutboundRepository(jdbc);

        assertThat(repository.enqueue(id, "!room:example.org", "task.completed", "done", NOW)).isTrue();
        assertThat(repository.enqueue(id, "!room:example.org", "task.completed", "done", NOW)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matrix_outbox_messages WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }
}

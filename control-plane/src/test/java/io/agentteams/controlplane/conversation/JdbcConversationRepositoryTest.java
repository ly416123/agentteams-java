package io.agentteams.controlplane.conversation;

import static org.assertj.core.api.Assertions.assertThat;

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

import io.agentteams.controlplane.security.ExecutionContext;

@Testcontainers(disabledWithoutDocker = true)
class JdbcConversationRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-1", "tenant-1", "project-1", "team-1", "alice");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = new JdbcTemplate(dataSource());
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM conversation_messages_cp");
        jdbc.update("DELETE FROM conversations");
    }

    @Test
    void createsAndFindsConversationWithinTenantProjectAndTeamScope() {
        UUID id = UUID.randomUUID();
        JdbcConversationRepository repository = new JdbcConversationRepository(jdbc);

        ConversationRecord created = repository.create(CONTEXT,
                new CreateConversationCommand(id, "Release planning", NOW));

        assertThat(created.id()).isEqualTo(id);
        assertThat(created.tenantId()).isEqualTo("tenant-1");
        assertThat(created.projectId()).isEqualTo("project-1");
        assertThat(created.teamId()).isEqualTo("team-1");
        assertThat(created.title()).isEqualTo("Release planning");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.version()).isZero();
        assertThat(repository.find(CONTEXT, id)).contains(created);
        assertThat(repository.find(new ExecutionContext("org-1", "tenant-2", "project-1", "team-1", "alice"), id))
                .isEmpty();
    }

    @Test
    void keepsConversationTeamNullable() {
        ExecutionContext projectContext = new ExecutionContext(
                "org-1", "tenant-1", "project-1", "team-1", "alice");
        ConversationRecord created = new JdbcConversationRepository(jdbc).create(projectContext,
                new CreateConversationCommand(UUID.randomUUID(), "Project conversation", NOW));

        assertThat(created.teamId()).isEqualTo("team-1");
        assertThat(jdbc.queryForObject("SELECT team_id FROM conversations WHERE id = ?", String.class, created.id()))
                .isEqualTo("team-1");
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

package io.agentteams.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.conversation.CreateConversationCommand;
import io.agentteams.controlplane.conversation.JdbcConversationRepository;
import io.agentteams.controlplane.security.ExecutionContext;
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
class TaskSourceConversationRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
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
        jdbc.update("DELETE FROM resource_scopes WHERE resource_type = 'TASK'");
        jdbc.update("DELETE FROM tasks");
    }

    @Test
    void sourceConversationIsNullableAndCanBeQueriedBack() {
        TaskRepository repository = new TaskRepository(jdbc);
        UUID taskWithoutConversation = UUID.randomUUID();
        UUID taskWithConversation = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        new JdbcConversationRepository(jdbc).create(
                new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice"),
                new CreateConversationCommand(conversationId, "Task source", NOW));

        repository.insert(TaskRecord.draft(taskWithoutConversation, "standalone", "", "alice", "test", NOW));
        repository.insert(TaskRecord.draft(taskWithConversation, "linked", "", "alice", "test", NOW)
                .withSourceConversationId(conversationId));
        jdbc.update("""
                INSERT INTO resource_scopes
                    (resource_type, resource_id, tenant_id, project_id, team, created_at, updated_at)
                VALUES ('TASK', ?, ?, ?, ?, ?, ?)
                """, taskWithConversation, "tenant-1", "project-1", "team-1",
                JdbcSupport.timestamp(NOW), JdbcSupport.timestamp(NOW));

        assertThat(repository.findById(taskWithoutConversation)).get().extracting(TaskRecord::sourceConversationId)
                .isNull();
        ExecutionContext context = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice");
        assertThat(repository.findBySourceConversation(context, conversationId))
                .extracting(TaskRecord::id)
                .containsExactly(taskWithConversation);
        assertThat(repository.findBySourceConversation(
                new ExecutionContext("org-2", "tenant-2", "project-1", "team-1", "bob"), conversationId))
                .isEmpty();
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

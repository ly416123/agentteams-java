package io.agentteams.controlplane.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
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
class JdbcConversationMessageRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-1", "tenant-1", "project-1", "team-1", "alice");
    private static JdbcTemplate jdbc;
    private UUID conversationId;

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
        conversationId = UUID.randomUUID();
        new JdbcConversationRepository(jdbc).create(CONTEXT,
                new CreateConversationCommand(conversationId, "Execution thread", NOW));
    }

    @Test
    void appendsMessagesFromDifferentChannelsWithStrictSequence() {
        JdbcConversationMessageRepository repository = new JdbcConversationMessageRepository(jdbc);

        ConversationMessageRecord console = repository.appendMessage(CONTEXT, message(
                "CONSOLE", "console message", "key-console"));
        ConversationMessageRecord matrix = repository.appendMessage(CONTEXT, message(
                "MATRIX", "matrix message", "key-matrix"));

        assertThat(console.sequence()).isEqualTo(1L);
        assertThat(matrix.sequence()).isEqualTo(2L);
        assertThat(repository.messages(CONTEXT, conversationId, 0, 10))
                .extracting(ConversationMessageRecord::channel)
                .containsExactly("CONSOLE", "MATRIX");
    }

    @Test
    void replaysTheOriginalMessageForTheSameConversationAndIdempotencyKey() {
        JdbcConversationMessageRepository repository = new JdbcConversationMessageRepository(jdbc);
        AppendConversationMessageCommand command = message("CONSOLE", "retry-safe", "same-key");

        ConversationMessageRecord first = repository.appendMessage(CONTEXT, command);
        ConversationMessageRecord replay = repository.appendMessage(CONTEXT, command);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.messages(CONTEXT, conversationId, 0, 10)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM conversations WHERE id = ?", Long.class, conversationId)).isEqualTo(1L);
    }

    @Test
    void doesNotExposeMessagesAcrossTenantScope() {
        JdbcConversationMessageRepository repository = new JdbcConversationMessageRepository(jdbc);
        repository.appendMessage(CONTEXT, message("CONSOLE", "private", "private-key"));

        ExecutionContext otherTenant = new ExecutionContext(
                "org-1", "tenant-2", "project-1", "team-1", "alice");
        assertThat(repository.messages(otherTenant, conversationId, 0, 10)).isEmpty();
    }

    private AppendConversationMessageCommand message(String channel, String content, String idempotencyKey) {
        return new AppendConversationMessageCommand(UUID.randomUUID(), conversationId, "USER", "alice",
                channel, "TEXT", content, null, "TEAM", NOW, idempotencyKey, "trace-1");
    }

    private static javax.sql.DataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

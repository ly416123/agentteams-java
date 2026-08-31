package io.agentteams.controlplane.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agentteams.controlplane.outbox.EventEnvelope;
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
class JdbcWebhookRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final WebhookScope SCOPE = new WebhookScope("org-1", "tenant-1", "project-1");

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
        jdbc.update("DELETE FROM webhook_deliveries");
        jdbc.update("DELETE FROM webhook_subscriptions");
    }

    @Test
    void persistsScopedSubscriptionsAndIdempotentDeliveries() {
        JdbcWebhookRepository repository = new JdbcWebhookRepository(jdbc);
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(), SCOPE,
                "https://hooks.example.com/agentteams", "secret-ref", Set.of("task.completed"), true, 0, NOW, NOW);
        repository.insert(subscription);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "task.completed", "task", UUID.randomUUID(),
                3, NOW, JsonNodeFactory.instance.objectNode().put("result", "ok"));

        assertThat(repository.listEnabled(SCOPE)).containsExactly(subscription);
        assertThat(repository.enqueue(subscription, event, NOW)).isTrue();
        assertThat(repository.enqueue(subscription, event, NOW)).isFalse();
        WebhookDelivery delivery = repository.findDue(NOW, 10).get(0);
        assertThat(delivery.eventId()).isEqualTo(event.eventId());
        assertThat(delivery.payloadJson()).contains("task.completed");

        repository.markRetry(delivery, NOW.plusSeconds(2), "temporary", NOW);
        WebhookDelivery retried = repository.findDue(NOW.plusSeconds(2), 10).get(0);
        assertThat(retried.attempts()).isEqualTo(1);
        repository.markSent(retried, NOW.plusSeconds(3));
        assertThat(repository.findDue(NOW.plusSeconds(3), 10)).isEmpty();
    }

    @Test
    void listIsolatedByOrganizationTenantAndProject() {
        JdbcWebhookRepository repository = new JdbcWebhookRepository(jdbc);
        repository.insert(new WebhookSubscription(UUID.randomUUID(), SCOPE, "https://hooks.example.com/one",
                "secret-one", Set.of("task.result"), true, 0, NOW, NOW));
        repository.insert(new WebhookSubscription(UUID.randomUUID(),
                new WebhookScope("org-1", "tenant-1", "project-2"), "https://hooks.example.com/two",
                "secret-two", Set.of("task.result"), true, 0, NOW, NOW));

        assertThat(repository.list(SCOPE)).hasSize(1);
        assertThat(repository.list(new WebhookScope("org-1", "tenant-2", "project-1"))).isEmpty();
    }
}

package io.agentteams.controlplane.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.outbox.EventEnvelope;
import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL storage for Webhook subscriptions and durable delivery attempts. */
@Repository
public class JdbcWebhookRepository implements WebhookRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcWebhookRepository(javax.sql.DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcWebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public WebhookSubscription insert(WebhookSubscription subscription) {
        try {
            jdbc.update("""
                    INSERT INTO webhook_subscriptions
                        (id, organization_id, tenant_id, project_id, endpoint, secret_ref, event_types,
                         enabled, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, subscription.id(), subscription.scope().organizationId(), subscription.scope().tenantId(),
                    subscription.scope().projectId(), subscription.endpoint(), subscription.secretRef(),
                    JdbcSupport.json(JdbcSupport.jsonArray(subscription.eventTypes().stream().sorted().toList())),
                    subscription.enabled(), subscription.version(), JdbcSupport.timestamp(subscription.createdAt()),
                    JdbcSupport.timestamp(subscription.updatedAt()));
            return subscription;
        } catch (DuplicateKeyException error) {
            throw new IllegalArgumentException("Webhook endpoint already exists in tenant", error);
        }
    }

    @Override
    public java.util.Optional<WebhookSubscription> findById(UUID id) {
        if (id == null) return java.util.Optional.empty();
        List<WebhookSubscription> subscriptions = jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, endpoint, secret_ref, event_types::text,
                       enabled, version, created_at, updated_at
                  FROM webhook_subscriptions
                 WHERE id = ?
                """, this::mapSubscription, id);
        return subscriptions.stream().findFirst();
    }

    @Override
    public List<WebhookSubscription> list(WebhookScope scope) {
        return querySubscriptions(scope, false);
    }

    @Override
    public List<WebhookSubscription> listEnabled(WebhookScope scope) {
        return querySubscriptions(scope, true);
    }

    private List<WebhookSubscription> querySubscriptions(WebhookScope scope, boolean enabledOnly) {
        String enabled = enabledOnly ? " AND enabled = TRUE" : "";
        return jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, endpoint, secret_ref, event_types::text,
                       enabled, version, created_at, updated_at
                  FROM webhook_subscriptions
                 WHERE organization_id = ? AND tenant_id = ?
                   AND project_id IS NOT DISTINCT FROM ?
                """ + enabled + " ORDER BY endpoint, id", this::mapSubscription,
                scope.organizationId(), scope.tenantId(), scope.projectId());
    }

    @Override
    @Transactional
    public boolean enqueue(WebhookSubscription subscription, EventEnvelope event, Instant now) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("event_id", event.eventId().toString());
        payload.put("event_type", event.eventType());
        payload.put("aggregate_type", event.aggregateType());
        payload.put("aggregate_id", event.aggregateId().toString());
        payload.put("aggregate_version", event.aggregateVersion());
        payload.put("occurred_at", event.occurredAt().toString());
        payload.set("payload", event.payload());
        payload.put("correlation_id", event.correlationId());
        payload.put("traceparent", event.traceparent());
        payload.put("tracestate", event.tracestate());
        return jdbc.update("""
                INSERT INTO webhook_deliveries
                    (id, subscription_id, event_id, endpoint, secret_ref, payload, status, attempts,
                     next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT (subscription_id, event_id) DO NOTHING
                """, UUID.randomUUID(), subscription.id(), event.eventId(), subscription.endpoint(),
                subscription.secretRef(), JdbcSupport.json(payload.toString()), JdbcSupport.timestamp(now),
                JdbcSupport.timestamp(now), JdbcSupport.timestamp(now)) == 1;
    }

    @Override
    public List<WebhookDelivery> findDue(Instant now, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                SELECT id, subscription_id, event_id, endpoint, secret_ref, payload::text, status, attempts,
                       next_attempt_at, created_at, updated_at, last_error
                  FROM webhook_deliveries
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY next_attempt_at, id
                 LIMIT ?
                """, this::mapDelivery, JdbcSupport.timestamp(now), limit);
    }

    @Override
    @Transactional
    public void markSent(WebhookDelivery delivery, Instant now) {
        jdbc.update("""
                UPDATE webhook_deliveries
                   SET status = 'SENT', updated_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, JdbcSupport.timestamp(now), delivery.id());
    }

    @Override
    @Transactional
    public void markRetry(WebhookDelivery delivery, Instant nextAttemptAt, String error, Instant now) {
        jdbc.update("""
                UPDATE webhook_deliveries
                   SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = ?,
                       last_error = ?, updated_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, JdbcSupport.timestamp(nextAttemptAt), error, JdbcSupport.timestamp(now), delivery.id());
    }

    @Override
    @Transactional
    public void markDead(WebhookDelivery delivery, String error, Instant now) {
        jdbc.update("""
                UPDATE webhook_deliveries
                   SET status = 'DEAD', attempts = attempts + 1, last_error = ?, updated_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, error, JdbcSupport.timestamp(now), delivery.id());
    }

    private WebhookSubscription mapSubscription(ResultSet rs, int row) throws SQLException {
        return new WebhookSubscription(rs.getObject("id", UUID.class),
                new WebhookScope(rs.getString("organization_id"), rs.getString("tenant_id"),
                        rs.getString("project_id")), rs.getString("endpoint"), rs.getString("secret_ref"),
                java.util.Set.copyOf(JdbcSupport.stringArray(rs.getString("event_types"))),
                rs.getBoolean("enabled"), rs.getLong("version"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"));
    }

    private WebhookDelivery mapDelivery(ResultSet rs, int row) throws SQLException {
        return new WebhookDelivery(rs.getObject("id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("endpoint"), rs.getString("secret_ref"),
                rs.getString("payload"), WebhookDelivery.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"), JdbcSupport.instant(rs, "next_attempt_at"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getString("last_error"));
    }
}

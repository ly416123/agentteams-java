package io.agentteams.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed inbound event idempotency store. */
public class JdbcInboundEventStore implements InboundEventStore {

    private static final String INSERT_EVENT = """
            INSERT INTO gateway_inbound_events (event_id, agent_id, connection_id, received_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public JdbcInboundEventStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcInboundEventStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean recordIfNew(String eventId, String agentId, UUID connectionId, Instant receivedAt) {
        requireText(eventId, "eventId");
        requireText(agentId, "agentId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(receivedAt, "receivedAt");
        return jdbc.update(INSERT_EVENT, eventId, agentId, connectionId, Timestamp.from(receivedAt)) == 1;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

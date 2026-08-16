package io.agentteams.gateway;

import com.google.protobuf.ByteString;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ServerMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed command log, delivery ledger, and per-agent ACK cursor. */
public class JdbcCommandEventStore implements CommandEventStore {

    private static final String ALLOCATE_SEQUENCE = """
            INSERT INTO gateway_agent_sequences (agent_id, last_sequence)
            VALUES (?, 1)
            ON CONFLICT (agent_id) DO UPDATE
                SET last_sequence = gateway_agent_sequences.last_sequence + 1
            RETURNING last_sequence
            """;
    private static final String INSERT_COMMAND = """
            INSERT INTO gateway_commands
                (agent_id, sequence, event_id, command_bytes, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String REPLAY_COMMANDS = """
            SELECT c.sequence, c.command_bytes
            FROM gateway_commands c
            LEFT JOIN gateway_ack_cursors a ON a.agent_id = c.agent_id
            WHERE c.agent_id = ?
              AND c.sequence > COALESCE(a.last_ack_sequence, 0)
            ORDER BY c.sequence
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCommandEventStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")), Clock.systemUTC());
    }

    public JdbcCommandEventStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcCommandEventStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SequencedCommand append(String agentId, ServerMessage command) {
        requireText(agentId, "agentId");
        Objects.requireNonNull(command, "command");
        if (!command.hasTaskAssigned()) {
            throw new IllegalArgumentException("only TaskAssigned commands are supported");
        }

        Long allocated = jdbc.queryForObject(ALLOCATE_SEQUENCE, Long.class, agentId);
        if (allocated == null || allocated <= 0) {
            throw new IllegalStateException("database returned an invalid command sequence");
        }
        EventMetadata metadata = command.getTaskAssigned().getMetadata().toBuilder()
                .setAgentId(agentId)
                .setSequence(allocated)
                .build();
        if (metadata.getEventId().isBlank()) {
            metadata = metadata.toBuilder().setEventId(UUID.randomUUID().toString()).build();
        }
        ServerMessage persisted = command.toBuilder()
                .setTaskAssigned(command.getTaskAssigned().toBuilder().setMetadata(metadata).build())
                .build();
        jdbc.update(INSERT_COMMAND, agentId, allocated, metadata.getEventId(), persisted.toByteArray(),
                Timestamp.from(clock.instant()));
        return new SequencedCommand(allocated, persisted);
    }

    @Override
    public List<SequencedCommand> replayUnacknowledged(String agentId) {
        requireText(agentId, "agentId");
        return jdbc.query(REPLAY_COMMANDS, (resultSet, rowNumber) -> readCommand(resultSet), agentId);
    }

    @Override
    public void markDelivered(String agentId, UUID connectionId, long sequence) {
        requireText(agentId, "agentId");
        Objects.requireNonNull(connectionId, "connectionId");
        requirePositive(sequence, "sequence");
        jdbc.update("""
                INSERT INTO gateway_command_deliveries
                    (agent_id, connection_id, sequence, delivered_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (agent_id, connection_id, sequence) DO NOTHING
                """, agentId, connectionId, sequence, Timestamp.from(clock.instant()));
    }

    @Override
    public AcknowledgementValidation validateAcknowledgement(String agentId, UUID connectionId, long sequence) {
        requireText(agentId, "agentId");
        Objects.requireNonNull(connectionId, "connectionId");
        requirePositive(sequence, "sequence");
        Long highest = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0)
                FROM gateway_command_deliveries
                WHERE agent_id = ? AND connection_id = ?
                """, Long.class, agentId, connectionId);
        Long exact = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM gateway_command_deliveries
                WHERE agent_id = ? AND connection_id = ? AND sequence = ?
                """, Long.class, agentId, connectionId, sequence);
        long highestValue = highest == null ? 0 : highest;
        if (exact != null && exact > 0 && sequence <= highestValue) {
            return AcknowledgementValidation.accepted(highestValue);
        }
        return AcknowledgementValidation.rejected(highestValue,
                "sequence was not durably delivered to this connection");
    }

    @Override
    public void acknowledge(String agentId, long sequence) {
        requireText(agentId, "agentId");
        requirePositive(sequence, "sequence");
        jdbc.update("""
                INSERT INTO gateway_ack_cursors (agent_id, last_ack_sequence, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (agent_id) DO UPDATE
                    SET last_ack_sequence = GREATEST(gateway_ack_cursors.last_ack_sequence, EXCLUDED.last_ack_sequence),
                        updated_at = EXCLUDED.updated_at
                """, agentId, sequence, Timestamp.from(clock.instant()));
    }

    @Override
    public long lastAcknowledgedSequence(String agentId) {
        requireText(agentId, "agentId");
        try {
            Long result = jdbc.queryForObject("""
                    SELECT COALESCE(last_ack_sequence, 0)
                    FROM gateway_ack_cursors
                    WHERE agent_id = ?
                    """, Long.class, agentId);
            return result == null ? 0 : result;
        } catch (EmptyResultDataAccessException missingCursor) {
            return 0;
        }
    }

    private static SequencedCommand readCommand(ResultSet resultSet) throws SQLException {
        long sequence = resultSet.getLong("sequence");
        try {
            return new SequencedCommand(sequence, ServerMessage.parseFrom(resultSet.getBytes("command_bytes")));
        } catch (com.google.protobuf.InvalidProtocolBufferException error) {
            throw new DataAccessException("stored command is not valid protobuf", error) {
            };
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}

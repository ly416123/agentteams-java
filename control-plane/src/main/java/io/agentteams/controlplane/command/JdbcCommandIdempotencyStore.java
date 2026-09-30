package io.agentteams.controlplane.command;

import io.agentteams.application.api.CommandResult;
import io.agentteams.application.api.CommandStatus;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed replay store keyed by tenant, command type, and idempotency key. */
@Repository
public class JdbcCommandIdempotencyStore implements CommandIdempotencyStore {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcCommandIdempotencyStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcCommandIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<CommandIdempotencyRecord> find(ExecutionContext context, String commandType,
            String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        return jdbc.query("""
                SELECT command_id, tenant_id, command_type, idempotency_key, request_hash,
                       status, deduplicated, task_id, aggregate_version, trace_id, created_at
                  FROM command_idempotency_keys
                 WHERE tenant_id = ? AND command_type = ? AND idempotency_key = ?
                """, this::map, context.tenantId(), commandType, idempotencyKey).stream().findFirst();
    }

    @Override
    public void save(ExecutionContext context, CommandIdempotencyRecord record) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(record, "record");
        try {
            jdbc.update("""
                    INSERT INTO command_idempotency_keys
                        (command_id, tenant_id, command_type, idempotency_key, request_hash,
                         status, deduplicated, task_id, aggregate_version, trace_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT (tenant_id, command_type, idempotency_key) DO NOTHING
                    """, record.commandId(), context.tenantId(), record.commandType(), record.idempotencyKey(),
                    record.requestHash(), record.result().status().name(), record.result().deduplicated(),
                    record.result().taskId(), record.result().aggregateVersion(), record.result().traceId(),
                    record.createdAt(), record.createdAt());
        } catch (DuplicateKeyException ignored) {
            // A concurrent request owns the tuple; the dispatcher will read the winner on replay.
        }
    }

    private CommandIdempotencyRecord map(ResultSet rs, int row) throws SQLException {
        CommandResult result = new CommandResult(rs.getObject("command_id", UUID.class),
                CommandStatus.valueOf(rs.getString("status")), rs.getBoolean("deduplicated"),
                rs.getObject("task_id", UUID.class), rs.getLong("aggregate_version"), rs.getString("trace_id"));
        return new CommandIdempotencyRecord(rs.getObject("command_id", UUID.class), rs.getString("tenant_id"),
                rs.getString("command_type"), rs.getString("idempotency_key"), rs.getString("request_hash"), result,
                rs.getTimestamp("created_at").toInstant());
    }
}

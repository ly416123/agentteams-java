package io.agentteams.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed projection of Agent connection presence and runtime metadata. */
public class JdbcAgentStateStore implements GatewayStateStore {

    private final JdbcTemplate jdbc;
    private final GatewayWorkerOperationObservationReporter operationObservations;

    public JdbcAgentStateStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                GatewayWorkerOperationObservationReporter.noop());
    }

    public JdbcAgentStateStore(DataSource dataSource, GatewayWorkerOperationObservationReporter operationObservations) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")), operationObservations);
    }

    public JdbcAgentStateStore(JdbcTemplate jdbc) {
        this(jdbc, GatewayWorkerOperationObservationReporter.noop());
    }

    public JdbcAgentStateStore(JdbcTemplate jdbc, GatewayWorkerOperationObservationReporter operationObservations) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.operationObservations = Objects.requireNonNull(operationObservations, "operationObservations");
    }

    @Override
    public void registered(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(at, "at");
        UUID agentId = canonicalAgentId(connection.agentId());
        updateCanonicalAgentOnRegister(agentId, connection.runtime(), connection.capabilities(), at);
        jdbc.update(upsertSql(), connection.agentId(), connection.connectionId(), "ONLINE", "READY",
                connection.runtime(), connection.runtimeVersion(), capabilitiesJson(connection.capabilities()),
                connection.specDigest(), connection.configRevision(), connection.secretGeneration(),
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
        reportOperation(connection, true, at);
    }

    @Override
    public boolean seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(at, "at");
        int owned = jdbc.update("""
                UPDATE gateway_agent_state
                   SET presence = 'ONLINE', phase = 'READY', runtime = ?, runtime_version = ?,
                       capabilities = ?::jsonb, spec_digest = ?, config_revision = ?, secret_generation = ?,
                       last_seen_at = ?, disconnected_at = NULL, updated_at = ?
                WHERE agent_id = ? AND connection_id = ?
                """, connection.runtime(), connection.runtimeVersion(), capabilitiesJson(connection.capabilities()),
                connection.specDigest(), connection.configRevision(), connection.secretGeneration(),
                Timestamp.from(at), Timestamp.from(at), connection.agentId(), connection.connectionId());
        if (owned == 0) {
            return false;
        }
        refreshCanonicalAgent(canonicalAgentId(connection.agentId()), connection.runtime(),
                connection.capabilities(), at);
        reportOperation(connection, true, at);
        return true;
    }

    @Override
    public boolean disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(at, "at");
        int owned = jdbc.update("""
                UPDATE gateway_agent_state
                   SET presence = 'OFFLINE', phase = 'DISCONNECTED', disconnected_at = ?, updated_at = ?
                 WHERE agent_id = ? AND connection_id = ?
                """, Timestamp.from(at), Timestamp.from(at), connection.agentId(), connection.connectionId());
        if (owned == 0) {
            return false;
        }
        updateCanonicalAgentOnDisconnect(canonicalAgentId(connection.agentId()), connection.runtime(),
                connection.capabilities(), at);
        reportOperation(connection, false, at);
        return true;
    }

    private void reportOperation(ConnectionRegistry.ConnectionSnapshot connection, boolean online, Instant at) {
        try {
            operationObservations.report(connection, online, at);
        } catch (RuntimeException ignored) {
            // Observation is auxiliary; a reporter failure must never break
            // the durable connection projection or gRPC lifecycle.
        }
    }

    private void updateCanonicalAgentOnDisconnect(UUID agentId, String runtime,
            Map<String, String> capabilities, Instant at) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET phase = CASE WHEN phase = 'TERMINATED' THEN 'TERMINATED' ELSE 'OFFLINE' END,
                       runtime = ?, capabilities = ?::jsonb, updated_at = ?, version = version + 1
                 WHERE id = ? AND phase IN ('PROVISIONING', 'READY', 'BUSY', 'DRAINING', 'TERMINATED', 'OFFLINE')
                """, runtime, capabilitiesJson(capabilities), Timestamp.from(at), agentId);
        if (updated == 0) {
            throw unknownOrInvalidAgent(agentId);
        }
    }

    private void updateCanonicalAgent(UUID agentId, String phase, String runtime,
            Map<String, String> capabilities, Instant at, String allowedPhases) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET phase = ?, runtime = ?, capabilities = ?::jsonb,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND phase IN (%s)
                """.formatted(allowedPhases), phase, runtime, capabilitiesJson(capabilities),
                Timestamp.from(at), agentId);
        if (updated == 0) {
            throw unknownOrInvalidAgent(agentId);
        }
    }

    private void updateCanonicalAgentOnRegister(UUID agentId, String runtime,
            Map<String, String> capabilities, Instant at) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET phase = CASE WHEN phase = 'DRAINING' THEN phase ELSE ? END,
                       runtime = ?, capabilities = ?::jsonb,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND phase IN ('PROVISIONING', 'OFFLINE', 'READY', 'DRAINING')
                """, "READY", runtime, capabilitiesJson(capabilities), Timestamp.from(at), agentId);
        if (updated == 0) {
            throw unknownOrInvalidAgent(agentId);
        }
    }

    private void refreshCanonicalAgent(UUID agentId, String runtime, Map<String, String> capabilities,
            Instant at) {
        int updated = jdbc.update("""
                UPDATE agents
                   SET runtime = ?, capabilities = ?::jsonb,
                       updated_at = ?, version = version + 1
                 WHERE id = ? AND phase <> 'FAILED'
                """, runtime, capabilitiesJson(capabilities), Timestamp.from(at), agentId);
        if (updated == 0) {
            throw unknownOrInvalidAgent(agentId);
        }
    }

    private static UUID canonicalAgentId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Agent Hello must use a canonical UUID: " + value,
                    exception);
        }
    }

    private static IllegalStateException unknownOrInvalidAgent(UUID agentId) {
        return new IllegalStateException("Agent is not registered or cannot change state: " + agentId);
    }

    private static String upsertSql() {
        return """
            INSERT INTO gateway_agent_state
                    (agent_id, connection_id, presence, phase, runtime, runtime_version, capabilities,
                     spec_digest, config_revision, secret_generation, connected_at, last_seen_at,
                     disconnected_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (agent_id) DO UPDATE SET
                    connection_id = EXCLUDED.connection_id,
                    presence = EXCLUDED.presence,
                    phase = EXCLUDED.phase,
                    runtime = EXCLUDED.runtime,
                    runtime_version = EXCLUDED.runtime_version,
                    capabilities = EXCLUDED.capabilities,
                    spec_digest = EXCLUDED.spec_digest,
                    config_revision = EXCLUDED.config_revision,
                    secret_generation = EXCLUDED.secret_generation,
                    connected_at = CASE WHEN EXCLUDED.presence = 'ONLINE'
                        THEN EXCLUDED.connected_at ELSE gateway_agent_state.connected_at END,
                    last_seen_at = EXCLUDED.last_seen_at,
                    disconnected_at = CASE WHEN EXCLUDED.presence = 'OFFLINE'
                        THEN EXCLUDED.disconnected_at ELSE NULL END,
                    updated_at = EXCLUDED.updated_at
                """;
    }

    private static String capabilitiesJson(Map<String, String> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return capabilities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        Objects.requireNonNull(value, "capability value");
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}

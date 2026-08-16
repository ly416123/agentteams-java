package io.agentteams.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed projection of Agent connection presence and runtime metadata. */
public class JdbcAgentStateStore implements GatewayStateStore {

    private final JdbcTemplate jdbc;

    public JdbcAgentStateStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcAgentStateStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void registered(AgentProfile profile, Instant at) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(at, "at");
        jdbc.update(upsertSql(false), profile.agentId(), "ONLINE", "READY", profile.runtime(),
                profile.runtimeVersion(), capabilitiesJson(profile.capabilities()), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at));
    }

    @Override
    public void seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(at, "at");
        jdbc.update(upsertSql(false), connection.agentId(), "ONLINE", "READY", connection.runtime(),
                connection.runtimeVersion(), capabilitiesJson(connection.capabilities()), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at));
    }

    @Override
    public void disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(at, "at");
        jdbc.update(upsertSql(true), connection.agentId(), "OFFLINE", "DISCONNECTED", connection.runtime(),
                connection.runtimeVersion(), capabilitiesJson(connection.capabilities()), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at));
    }

    private static String upsertSql(boolean disconnected) {
        return """
                INSERT INTO gateway_agent_state
                    (agent_id, presence, phase, runtime, runtime_version, capabilities,
                     connected_at, last_seen_at, disconnected_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (agent_id) DO UPDATE SET
                    presence = EXCLUDED.presence,
                    phase = EXCLUDED.phase,
                    runtime = EXCLUDED.runtime,
                    runtime_version = EXCLUDED.runtime_version,
                    capabilities = EXCLUDED.capabilities,
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

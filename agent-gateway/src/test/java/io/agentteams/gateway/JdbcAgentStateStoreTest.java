package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcAgentStateStoreTest {

    @Test
    void persistsRegistrationProjectionAndCapabilities() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);

        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot connection = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), agentId.toString(), "deepseek-worker", "1.2.3",
                Map.of("gpu", "true", "tasks", "v1"), Instant.parse("2026-08-16T00:00:00Z"), 0);
        store.registered(connection, Instant.parse("2026-08-16T00:00:00Z"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("gateway_agent_state"), args.capture());
        Object[] values = args.getValue();
        assertThat(values).contains(agentId.toString(), "ONLINE", "READY", "deepseek-worker");
        assertThat(values).anyMatch(value -> value instanceof String json
                && json.contains("\"gpu\":\"true\"")
                && json.contains("\"tasks\":\"v1\""));

        ArgumentCaptor<Object[]> canonicalArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE agents"), canonicalArgs.capture());
        assertThat(canonicalArgs.getValue()).contains(agentId, "READY", "deepseek-worker");
    }

    @Test
    void persistsWorkerVersionFactsInGatewayProjection() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot connection = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), agentId.toString(), "qwenpaw", "0.4.0",
                "sha256:worker-v2", "config-17", "secret-9", Map.of(),
                Instant.parse("2026-08-16T00:00:00Z"), 0);

        store.registered(connection, Instant.parse("2026-08-16T00:00:00Z"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("gateway_agent_state"), args.capture());
        assertThat(args.getValue()).containsExactly(
                agentId.toString(), connection.connectionId(), "ONLINE", "READY", "qwenpaw", "0.4.0", "{}",
                "sha256:worker-v2", "config-17", "secret-9",
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")));
    }

    @Test
    void registrationDoesNotUndoARequestedDrain() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot connection = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), agentId.toString(), "qwenpaw", "1.0", Map.of(),
                Instant.parse("2026-08-16T00:00:00Z"), 0);

        store.registered(connection, Instant.parse("2026-08-16T00:00:01Z"));

        verify(jdbc).update(contains("phase = CASE"), any(Object[].class));
    }

    @Test
    void recordsSeenAndDisconnectedPresenceTransitions() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE gateway_agent_state"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot snapshot = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), agentId.toString(), "runtime", "1.0", Map.of("tasks", "v1"),
                Instant.parse("2026-08-16T00:00:00Z"), 4);

        store.seen(snapshot, Instant.parse("2026-08-16T00:00:01Z"));
        store.disconnected(snapshot, Instant.parse("2026-08-16T00:00:02Z"));

        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("gateway_agent_state"),
                any(Object[].class));
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("UPDATE agents"),
                any(Object[].class));

        ArgumentCaptor<Object[]> canonicalArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("UPDATE agents"),
                canonicalArgs.capture());
        assertThat(canonicalArgs.getAllValues().get(1)).contains(agentId);
    }

    @Test
    void seenRefreshesVersionFactsInTheirDeclaredSqlPositions() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE gateway_agent_state"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        UUID connectionId = UUID.fromString("3c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot snapshot = new ConnectionRegistry.ConnectionSnapshot(
                connectionId, agentId.toString(), "qwenpaw", "0.4.0", "digest", "config", "secret",
                Map.of(), Instant.parse("2026-08-16T00:00:00Z"), 0);

        store.seen(snapshot, Instant.parse("2026-08-16T00:00:01Z"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE gateway_agent_state"), args.capture());
        assertThat(args.getValue()[0]).isEqualTo("qwenpaw");
        assertThat(args.getValue()[1]).isEqualTo("0.4.0");
        assertThat(args.getValue()[3]).isEqualTo("digest");
        assertThat(args.getValue()[4]).isEqualTo("config");
        assertThat(args.getValue()[5]).isEqualTo("secret");
        assertThat(args.getValue()[8]).isEqualTo(agentId.toString());
        assertThat(args.getValue()[9]).isEqualTo(connectionId);
    }

    @Test
    void heartbeatRefreshDoesNotInvalidateLifecycleVersionGuards() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE agents"), any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("UPDATE gateway_agent_state"), any(Object[].class))).thenReturn(1);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID agentId = UUID.fromString("0c1e0f9f-e0d3-4b5a-9e4f-3d9f7c0e7f01");
        ConnectionRegistry.ConnectionSnapshot snapshot = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), agentId.toString(), "qwenpaw", "0.4.0", Map.of(),
                Instant.parse("2026-08-16T00:00:00Z"), 0);

        store.seen(snapshot, Instant.parse("2026-08-16T00:00:01Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        String canonicalSql = sql.getAllValues().stream()
                .filter(value -> value.contains("UPDATE agents"))
                .findFirst()
                .orElseThrow();
        assertThat(canonicalSql).doesNotContain("version = version + 1");
    }

    @Test
    void rejectsUnknownAgentBeforeWritingProjection() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        UUID unknownId = UUID.fromString("c7a0d5fd-4d9a-4ce4-93f6-1b758a8cfca2");
        ConnectionRegistry.ConnectionSnapshot unknown = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), unknownId.toString(), "runtime", "1.0", Map.of(),
                Instant.parse("2026-08-16T00:00:00Z"), 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.registered(
                        unknown,
                        Instant.parse("2026-08-16T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknownId.toString());
        verify(jdbc, org.mockito.Mockito.never()).update(contains("gateway_agent_state"),
                any(Object[].class));
    }
}

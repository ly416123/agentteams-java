package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

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
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);

        store.registered(new AgentProfile("agent-1", "deepseek-worker", "1.2.3",
                Map.of("gpu", "true", "tasks", "v1")), Instant.parse("2026-08-16T00:00:00Z"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("gateway_agent_state"), args.capture());
        Object[] values = args.getValue();
        assertThat(values).contains("agent-1", "ONLINE", "READY", "deepseek-worker");
        assertThat(values).anyMatch(value -> value instanceof String json
                && json.contains("\"gpu\":\"true\"")
                && json.contains("\"tasks\":\"v1\""));
    }

    @Test
    void recordsSeenAndDisconnectedPresenceTransitions() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        JdbcAgentStateStore store = new JdbcAgentStateStore(jdbc);
        ConnectionRegistry.ConnectionSnapshot snapshot = new ConnectionRegistry.ConnectionSnapshot(
                UUID.randomUUID(), "agent-1", "runtime", "1.0", Map.of("tasks", "v1"),
                Instant.parse("2026-08-16T00:00:00Z"), 4);

        store.seen(snapshot, Instant.parse("2026-08-16T00:00:01Z"));
        store.disconnected(snapshot, Instant.parse("2026-08-16T00:00:02Z"));

        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("gateway_agent_state"),
                any(Object[].class));
    }
}

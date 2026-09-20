package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ConfigLifecycleRepositoryTest {
    @Test
    void findsAllCurrentBindingsForOneAgentInStableOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID agentId = UUID.randomUUID();
        ConfigBindingRecord first = binding("agent-spec:a", agentId);
        ConfigBindingRecord second = binding("agent-spec:b", agentId);
        when(jdbc.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<ConfigBindingRecord>>any(), eq(agentId)))
                .thenReturn(List.of(first, second));

        assertThat(new ConfigLifecycleRepository(jdbc).findBindingsForAgent(agentId))
                .containsExactly(first, second);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("WHERE agent_id = ?")
                        && sql.contains("ORDER BY subject, id")),
                org.mockito.ArgumentMatchers.<RowMapper<ConfigBindingRecord>>any(), eq(agentId));
    }

    private static ConfigBindingRecord binding(String subject, UUID agentId) {
        return new ConfigBindingRecord(UUID.randomUUID(), subject, agentId, UUID.randomUUID(),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}

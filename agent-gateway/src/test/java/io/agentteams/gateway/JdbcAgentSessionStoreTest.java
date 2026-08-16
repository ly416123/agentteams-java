package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAgentSessionStoreTest {
    @Test
    void delegatesHashedLookupToDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID agentId = UUID.randomUUID();
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Object.class))).thenAnswer(invocation ->
                java.util.List.of(new AgentSession(agentId, "a".repeat(64),
                        java.time.Instant.parse("2026-08-16T00:01:00Z"), false)));
        assertThat(new JdbcAgentSessionStore(jdbc).findByTokenSha256("a".repeat(64)))
                .get().extracting(AgentSession::agentId).isEqualTo(agentId);
    }
}

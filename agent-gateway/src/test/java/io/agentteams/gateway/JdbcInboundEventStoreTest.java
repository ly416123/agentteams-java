package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcInboundEventStoreTest {

    @Test
    void recordsEventOnlyWhenUniqueConstraintAcceptedIt() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        doReturn(1, 0).when(jdbc).update(anyString(), any(Object[].class));
        JdbcInboundEventStore store = new JdbcInboundEventStore(jdbc);
        UUID connectionId = UUID.randomUUID();

        assertThat(store.recordIfNew("event-1", "agent-1", connectionId,
                Instant.parse("2026-08-16T00:00:00Z"))).isTrue();
        assertThat(store.recordIfNew("event-1", "agent-1", connectionId,
                Instant.parse("2026-08-16T00:00:01Z"))).isFalse();

        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("gateway_inbound_events"),
                any(Object[].class));
    }
}

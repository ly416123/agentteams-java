package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTaskSourceEvidenceRepositoryTest {

    @Test
    void rejectsCaptureWhenActiveRunInsertSelectPersistsNoCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTaskSourceEvidenceRepository repository = new JdbcTaskSourceEvidenceRepository(jdbc);

        assertThatThrownBy(() -> repository.save(new ExecutionContext("org", "tenant", "project", "team", "worker"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "call", "search", "idempotency",
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source evidence call was not persisted");
    }
}

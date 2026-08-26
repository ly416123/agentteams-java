package io.agentteams.controlplane.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TaskSandboxRepositoryTest {
    @Test
    void separatesTableNameFromLookupPredicateInFindByIdQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID sandboxId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(sandboxId))).thenReturn(List.of());

        new TaskSandboxRepository(jdbc).findById(sandboxId);

        verify(jdbc).query(contains("FROM task_sandboxes WHERE"), any(RowMapper.class), eq(sandboxId));
    }
}

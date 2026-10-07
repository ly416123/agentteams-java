package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTaskProcessEventRepositoryTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void permitsOnlyTheSingleExtraObservationNeededToDetectTruncation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        JdbcTaskProcessEventRepository repository = new JdbcTaskProcessEventRepository(jdbc);
        ExecutionContext context = new ExecutionContext("org", "tenant", "project", "team", "actor");

        assertThat(repository.find(context, UUID.randomUUID(), UUID.randomUUID(), 0,
                Set.of(TaskEventVisibility.REQUESTER), 1001)).isEmpty();
        assertThatThrownBy(() -> repository.find(context, UUID.randomUUID(), UUID.randomUUID(), 0,
                Set.of(TaskEventVisibility.REQUESTER), 1002)).isInstanceOf(IllegalArgumentException.class);
    }
}

package io.agentteams.controlplane.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TaskSandboxRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void separatesTableNameFromLookupPredicateInFindByIdQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID sandboxId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(sandboxId))).thenReturn(List.of());

        new TaskSandboxRepository(jdbc).findById(sandboxId);

        verify(jdbc).query(contains("FROM task_sandboxes WHERE"), any(RowMapper.class), eq(sandboxId));
    }

    @Test
    void claimRequestedUsesTheSharedProjectionAndClaimsOperationLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new TaskSandboxRepository(jdbc).claimRequested(NOW, 4, "cp-1", NOW.plusSeconds(30));

        verify(jdbc).query(contains("provider_resource_uid"), any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("operation_owner"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void providerFailureReleasesLeaseIncrementsRetryAndSchedulesBackoff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID sandboxId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(1);

        new TaskSandboxRepository(jdbc).releaseOperation(sandboxId, 7, "cp-1", "PROVISION", 5,
                Duration.ofSeconds(2), Duration.ofSeconds(30), "KUBERNETES_UNAVAILABLE", "temporarily unavailable",
                NOW);

        verify(jdbc).update(contains("retry_count = retry_count + 1"), any(Object[].class));
    }
}

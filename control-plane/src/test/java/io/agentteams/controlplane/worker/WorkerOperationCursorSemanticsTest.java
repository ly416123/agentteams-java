package io.agentteams.controlplane.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.api.CursorPageRequest;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WorkerOperationCursorSemanticsTest {
    private static final Principal PRINCIPAL = new Principal("actor-a",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("read"));

    @Test
    void publicOperationPageUsesUpdatedAtForItsTupleCursorAndOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new WorkerOperationRepository(jdbc).findPage(UUID.randomUUID(), PRINCIPAL,
                new CursorPageRequest.Position(java.time.Instant.parse("2026-08-29T00:00:00.123456789Z"),
                        UUID.randomUUID()), 21, CursorPageRequest.Direction.DESC);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        org.assertj.core.api.Assertions.assertThat(sql.getValue())
                .contains("(operation.updated_at, operation.id) < (?, ?)")
                .contains("ORDER BY operation.updated_at DESC, operation.id DESC")
                .doesNotContain("ORDER BY operation.created_at");
    }
}

package io.agentteams.controlplane.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.api.CursorPageRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcProjectListRepositoryTest {
    @Test
    void projectListUsesStatusAndSearchPredicatesAfterTenantAndActorMembership() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new JdbcProjectRepository(jdbc).findProjects("tenant-a", "actor-a", null, 21,
                CursorPageRequest.Direction.DESC, "ACTIVE", "console");

        verify(jdbc).query(contains("p.tenant_id = ?"), any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("m.subject = ? AND m.status = 'ACTIVE'"), any(RowMapper.class),
                any(Object[].class));
        verify(jdbc).query(contains("p.status = ?"), any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("p.name ILIKE ?"), any(RowMapper.class), any(Object[].class));
    }
}

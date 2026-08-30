package io.agentteams.controlplane.persistence;

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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcScopedResourceListRepositoryTest {
    private static final Principal PRINCIPAL = new Principal("actor-a",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("read"));

    @Test
    void teamListUsesStatusAndSearchPredicatesAfterScopePredicates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new TeamRepository(jdbc).findPage(PRINCIPAL, null, 21, CursorPageRequest.Direction.DESC,
                "ACTIVE", "research");

        verify(jdbc).query(contains("s.tenant_id = ? AND s.project_id = ? AND s.team = ?"),
                any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("t.status = ?"), any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("(t.name ILIKE ? OR t.display_name ILIKE ?)"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void scopedTeamListResolvesLogicalProjectNamesToProjectMemberships() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new TeamRepository(jdbc).findPage(PRINCIPAL, null, 21, CursorPageRequest.Direction.DESC);

        verify(jdbc).query(contains("JOIN projects p ON p.id = m.project_id"),
                any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("p.name = s.project_id"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void agentListUsesPhaseAndSearchPredicatesAfterScopePredicates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new AgentRepository(jdbc).findPage(PRINCIPAL, null, 21, CursorPageRequest.Direction.DESC,
                "READY", "worker");

        verify(jdbc).query(contains("s.tenant_id = ? AND s.project_id = ? AND s.team = ?"),
                any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("a.phase = ?"), any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(contains("(a.name ILIKE ? OR a.runtime ILIKE ?)"),
                any(RowMapper.class), any(Object[].class));
    }
}

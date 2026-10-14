package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ResourceScopeRepositoryTest {
    @AfterEach
    void clearPrincipal() { PrincipalContext.clear(); }

    @Test
    void unauthenticatedDevelopmentCallsKeepLegacyVisibilityCompatibility() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new ResourceScopeRepository(jdbc).visible("TEAM", UUID.randomUUID())).isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    void matchesConfiguredUuidScopeThroughLegacyTenantAndProjectAliases() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Principal caller = new Principal("subject-1", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());
        UUID tenantId = UUID.fromString("429327d7-0250-600e-b51c-31d853528b8f");
        UUID projectId = UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447");
        UUID teamId = UUID.fromString("b7aac93d-2e8c-4774-b04b-c80f839d4915");

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] parameters = invocation.getArguments().length == 3
                    && invocation.getArgument(2) instanceof Object[] values
                    ? values : java.util.Arrays.copyOfRange(invocation.getArguments(), 2,
                            invocation.getArguments().length);
            assertThat(sql).contains("legacy_tenant_mappings", "resource_scopes", "project_memberships");
            assertThat(List.of(parameters)).contains(tenantId, projectId, teamId,
                    "tenant-a", "project-a", "team-a", "subject-1");
            return List.of(Boolean.TRUE);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(new ResourceScopeRepository(jdbc)
                .matchesEvaluationTeamScope(caller, tenantId, projectId, teamId)).isTrue();
    }
}

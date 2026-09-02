package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;

class ExecutionContextResolverTest {

    @Test
    void resolvesLegacyScopeToUnifiedExecutionContext() {
        ExecutionContext expected = new ExecutionContext("org-acme", "tenant-prod", "project-1", "team-1", "user-1");
        ExecutionContextResolver resolver = new ExecutionContextResolver((tenant, project, team, subject) ->
                Optional.of(expected));

        ExecutionContext actual = resolver.resolve(new Principal("user-1",
                new AuthorizationService.Scope("legacy-tenant", "project-1", "team-1"), Set.of()));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void rejectsMissingTenantMapping() {
        ExecutionContextResolver resolver = new ExecutionContextResolver((tenant, project, team, subject) ->
                Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(new Principal("user-1",
                new AuthorizationService.Scope("legacy-tenant", "project-1", "team-1"), Set.of())))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("authenticated scope has no organization/tenant mapping");
    }

    @Test
    void rejectsDirectoryResultWithDifferentSubject() {
        ExecutionContextResolver resolver = new ExecutionContextResolver((tenant, project, team, subject) ->
                Optional.of(new ExecutionContext("org-acme", "tenant-prod", "project-1", "team-1", "other-user")));

        assertThatThrownBy(() -> resolver.resolve(new Principal("user-1",
                new AuthorizationService.Scope("legacy-tenant", "project-1", "team-1"), Set.of())))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("execution context subject does not match authenticated principal");
    }

    @Test
    void rejectsBlankContextParts() {
        assertThatThrownBy(() -> new ExecutionContext("org-acme", "tenant-prod", "", "team-1", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("projectId must not be blank");
    }

    @Test
    void exposesResolvedContextFromPrincipalContext() {
        ExecutionContext expected = new ExecutionContext("org-acme", "tenant-prod", "project-1", "team-1", "user-1");
        ExecutionContextResolver resolver = new ExecutionContextResolver((tenant, project, team, subject) ->
                Optional.of(expected));
        Principal principal = new Principal("user-1",
                new AuthorizationService.Scope("legacy-tenant", "project-1", "team-1"), Set.of());
        try {
            PrincipalContext.set(principal);
            assertThat(PrincipalContext.executionContext(resolver)).contains(expected);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void returnsEmptyContextForUnauthenticatedCalls() {
        PrincipalContext.clear();

        assertThat(PrincipalContext.executionContext(new ExecutionContextResolver((tenant, project, team, subject) ->
                Optional.empty()))).isEmpty();
    }

    @Test
    void distinguishesResourceScopeFromSubjectIdentity() {
        ExecutionContext first = new ExecutionContext("org-acme", "tenant-prod", "project-1", "team-1", "user-1");
        ExecutionContext otherSubject = new ExecutionContext("org-acme", "tenant-prod", "project-1", "team-1", "user-2");

        assertThat(first.sameResourceScope(otherSubject)).isTrue();
        assertThat(first.belongsTo(otherSubject)).isFalse();
        assertThat(first.belongsTo(first)).isTrue();
    }

    @Test
    void jdbcDirectoryRequiresActiveProjectMembershipForTheResolvedScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new JdbcExecutionContextDirectory(jdbc).resolve("tenant-a", "project-a", "team-a", "user-a");

        verify(jdbc).query(contains("project_memberships"), any(RowMapper.class), any(Object[].class));
    }
}

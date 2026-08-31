package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
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
}

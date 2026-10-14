package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExecutionContextResolverTest {
    private static final UUID PROJECT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void resolvesProjectNameToCanonicalUuidBeforeLookingUpExecutionContext() {
        ExecutionContext expected = context(PROJECT_ID.toString(), "user-1");
        AtomicReference<String> resolvedProject = new AtomicReference<>();
        ExecutionContextResolver resolver = resolver((tenant, project, team, subject) -> {
            resolvedProject.set(project);
            return Optional.of(expected);
        });

        ExecutionContext actual = resolver.resolve(principal("user-1"));

        assertThat(actual).isEqualTo(expected);
        assertThat(resolvedProject).hasValue(PROJECT_ID.toString());
    }

    @Test
    void rejectsMissingTenantMapping() {
        ExecutionContextResolver resolver = resolver((tenant, project, team, subject) -> Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(principal("user-1")))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("authenticated scope has no organization/tenant mapping");
    }

    @Test
    void rejectsDirectoryResultWithDifferentSubject() {
        ExecutionContextResolver resolver = resolver((tenant, project, team, subject) ->
                Optional.of(context(PROJECT_ID.toString(), "other-user")));

        assertThatThrownBy(() -> resolver.resolve(principal("user-1")))
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
        ExecutionContext expected = context(PROJECT_ID.toString(), "user-1");
        ExecutionContextResolver resolver = resolver((tenant, project, team, subject) -> Optional.of(expected));
        try {
            PrincipalContext.set(principal("user-1"));
            assertThat(PrincipalContext.executionContext(resolver)).contains(expected);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void returnsEmptyContextForUnauthenticatedCalls() {
        PrincipalContext.clear();

        assertThat(PrincipalContext.executionContext(resolver((tenant, project, team, subject) -> Optional.empty())))
                .isEmpty();
    }

    @Test
    void distinguishesResourceScopeFromSubjectIdentity() {
        ExecutionContext first = context(PROJECT_ID.toString(), "user-1");
        ExecutionContext otherSubject = context(PROJECT_ID.toString(), "user-2");

        assertThat(first.sameResourceScope(otherSubject)).isTrue();
        assertThat(first.belongsTo(otherSubject)).isFalse();
        assertThat(first.belongsTo(first)).isTrue();
    }

    @Test
    void jdbcDirectoryRequiresActiveProjectMembershipForTheResolvedScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        new JdbcExecutionContextDirectory(jdbc).resolve("tenant-a", PROJECT_ID.toString(), "team-a", "user-a");

        verify(jdbc).query(contains("project_memberships"), any(RowMapper.class), any(Object[].class));
    }

    private static ExecutionContextResolver resolver(ExecutionContextResolver.ScopeDirectory directory) {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(PROJECT_ID, "legacy-tenant", "project-1", "user-1", Instant.EPOCH);
        when(projects.findProjectByName("legacy-tenant", "project-1")).thenReturn(Optional.of(project));
        when(projects.findProject("legacy-tenant", PROJECT_ID)).thenReturn(Optional.of(project));
        when(projects.findMembership("legacy-tenant", PROJECT_ID, "user-1")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("legacy-tenant", PROJECT_ID, "user-1", ProjectRole.DEVELOPER,
                        Instant.EPOCH)));
        return new ExecutionContextResolver(directory, new ProjectScopeResolver(projects));
    }

    private static Principal principal(String subject) {
        return new Principal(subject, new AuthorizationService.Scope("legacy-tenant", "project-1", "team-1"), Set.of());
    }

    private static ExecutionContext context(String projectId, String subject) {
        return new ExecutionContext("org-acme", "tenant-prod", projectId, "team-1", subject);
    }
}

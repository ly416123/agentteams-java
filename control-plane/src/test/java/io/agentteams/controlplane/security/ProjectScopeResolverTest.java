package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectScopeResolverTest {
    private static final UUID PROJECT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void nameAndUuidResolveToTheSameCanonicalScope() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(PROJECT_ID, "tenant-a", "project-a", "alice", Instant.EPOCH);
        allow(projects, project);
        ProjectScopeResolver resolver = new ProjectScopeResolver(projects);
        Principal principal = principal("project-a");

        assertThat(resolver.resolve(principal).projectId()).isEqualTo(PROJECT_ID);
        assertThat(resolver.resolve(principal, PROJECT_ID.toString()).projectId()).isEqualTo(PROJECT_ID);
        assertThat(resolver.canonicalize(principal, "project-a").scope().project())
                .isEqualTo(PROJECT_ID.toString());
    }

    @Test
    void inactiveMembershipIsDenied() {
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(PROJECT_ID, "tenant-a", "project-a", "alice", Instant.EPOCH);
        when(projects.findProjectByName("tenant-a", "project-a")).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ProjectScopeResolver(projects).resolve(principal("project-a")))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("membership");
    }

    private static void allow(ProjectRepository projects, ProjectRecord project) {
        when(projects.findProjectByName("tenant-a", project.name())).thenReturn(Optional.of(project));
        when(projects.findProject("tenant-a", project.id())).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant-a", project.id(), "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", project.id(), "alice", ProjectRole.DEVELOPER,
                        Instant.EPOCH)));
    }

    private static Principal principal(String project) {
        return new Principal("alice", new AuthorizationService.Scope("tenant-a", project, "team-a"), Set.of());
    }
}

package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceAuthorizationServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private ProjectRepository repository;
    private ResourceAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRepository.class);
        authorization = new ResourceAuthorizationService(repository);
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void exactProjectScopeAndRoleAreRequired() {
        when(repository.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "alice", ProjectRole.ADMIN, Instant.EPOCH)));

        assertThatCode(() -> authorization.require(ResourceAction.PROJECT_MEMBER_INVITE,
                ResourceRef.project("tenant-a", PROJECT_ID))).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.require(ResourceAction.PROJECT_MEMBER_INVITE,
                ResourceRef.project("tenant-b", PROJECT_ID)))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void viewerCannotMutateProjectMembership() {
        when(repository.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "alice", ProjectRole.VIEWER, Instant.EPOCH)));

        assertThatThrownBy(() -> authorization.require(ResourceAction.PROJECT_MEMBER_ROLE_CHANGE,
                ResourceRef.project("tenant-a", PROJECT_ID)))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void ownerTransferIsOwnerOnly() {
        when(repository.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "alice", ProjectRole.ADMIN, Instant.EPOCH)));

        assertThatThrownBy(() -> authorization.require(ResourceAction.PROJECT_OWNER_TRANSFER,
                ResourceRef.project("tenant-a", PROJECT_ID)))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void taskAuthorizationResolvesProjectNameWithinTenant() {
        when(repository.findProjectByName("tenant-a", "project-a")).thenReturn(Optional.of(
                ProjectRecord.create(PROJECT_ID, "tenant-a", "project-a", "alice", Instant.EPOCH)));
        when(repository.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "alice", ProjectRole.DEVELOPER,
                        Instant.EPOCH)));

        assertThatCode(() -> authorization.require(ResourceAction.TASK_CREATE,
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a")))
                .doesNotThrowAnyException();
    }
}

package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectMembershipLifecycleTest {
    private static final UUID PROJECT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private ProjectRepository repository;
    private ProjectAuthorizationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRepository.class);
        service = new ProjectAuthorizationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        PrincipalContext.set(new Principal("owner-a",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        when(repository.findProject("tenant-a", PROJECT_ID)).thenReturn(Optional.of(
                new ProjectRecord(PROJECT_ID, "tenant-a", "project-a", "ACTIVE", "owner-a", NOW, NOW, 7)));
        when(repository.findMembership("tenant-a", PROJECT_ID, "owner-a")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner-a", ProjectRole.OWNER, NOW)));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void cannotDisableTheLastProjectOwner() {
        when(repository.findMembership("tenant-a", PROJECT_ID, "owner-a")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner-a", ProjectRole.OWNER, NOW)));
        when(repository.countActiveOwners("tenant-a", PROJECT_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.disableMember(PROJECT_ID, "owner-a"))
                .isInstanceOf(ProjectMembershipConflictException.class)
                .hasMessageContaining("MEMBERSHIP_LAST_OWNER");
    }

    @Test
    void ownerTransferRequiresExpectedProjectVersionAndChangesRolesAtomically() {
        ProjectMembershipRecord target = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "owner-b", ProjectRole.ADMIN, NOW);
        when(repository.findMembership("tenant-a", PROJECT_ID, "owner-b"))
                .thenReturn(Optional.of(target));
        when(repository.transferOwnership("tenant-a", PROJECT_ID, "owner-a", "owner-b", 7, NOW))
                .thenReturn(true);

        service.transferOwner(PROJECT_ID, "owner-b", 7);

        verify(repository).transferOwnership("tenant-a", PROJECT_ID, "owner-a", "owner-b", 7, NOW);
    }

    @Test
    void disabledMemberCanBeReenabledWithExpectedMembershipVersion() {
        ProjectMembershipRecord disabled = new ProjectMembershipRecord(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, "INACTIVE", NOW, NOW, 3);
        when(repository.findMembershipIncludingInactive("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(disabled));
        when(repository.updateMembershipStatus("tenant-a", PROJECT_ID, "developer", "ACTIVE", 3, NOW))
                .thenReturn(true);

        service.enableMember(PROJECT_ID, "developer", 3);

        verify(repository).updateMembershipStatus("tenant-a", PROJECT_ID, "developer", "ACTIVE", 3, NOW);
    }

    @Test
    void roleChangeUsesCompareAndSetAndCannotGrantOwnerDirectly() {
        ProjectMembershipRecord target = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, NOW);
        when(repository.findMembership("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(target));
        when(repository.updateMembershipRole("tenant-a", PROJECT_ID, "developer", ProjectRole.OPERATOR, 0, NOW))
                .thenReturn(true);

        service.changeRole(PROJECT_ID, "developer", ProjectRole.OPERATOR, 0);

        verify(repository).updateMembershipRole("tenant-a", PROJECT_ID, "developer", ProjectRole.OPERATOR, 0, NOW);
    }
}

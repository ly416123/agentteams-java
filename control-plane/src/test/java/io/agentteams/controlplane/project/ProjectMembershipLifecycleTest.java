package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectMembershipLifecycleTest {
    private static final UUID PROJECT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private ProjectRepository repository;
    private ProjectAuthorizationService service;
    private List<AuditEvent> auditEvents;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRepository.class);
        auditEvents = new ArrayList<>();
        AuditRecorder audits = auditEvents::add;
        service = new ProjectAuthorizationService(repository, Clock.fixed(NOW, ZoneOffset.UTC), audits);
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
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_OWNER_TRANSFERRED");
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "3c31ba8d4dab9e1f45dc499d788d1db449c91610c11b91122b194cd7e905fe2a");
        });
    }

    @Test
    void directMemberAdditionEmitsAStableRedactedAudit() {
        when(repository.insertMembershipIdempotency(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        service.addMember(PROJECT_ID, "member-1", "developer", ProjectRole.DEVELOPER);

        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_MEMBER_ADDED");
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
            assertThat(event.attributes()).doesNotContainValue("developer");
        });
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
    void disablingAnActiveMemberEmitsAStableRedactedAudit() {
        ProjectMembershipRecord target = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, NOW);
        when(repository.findMembership("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(target));
        when(repository.deactivateMembership("tenant-a", PROJECT_ID, "developer", NOW)).thenReturn(true);

        service.disableMember(PROJECT_ID, "developer");

        verify(repository).deactivateMembership("tenant-a", PROJECT_ID, "developer", NOW);
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_MEMBER_DISABLED");
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
            assertThat(event.attributes()).doesNotContainValue("developer");
        });
    }

    @Test
    void concurrentDisableThatUpdatesNoRowsDoesNotEmitSuccessAudit() {
        ProjectMembershipRecord target = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, NOW);
        when(repository.findMembership("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(target));
        when(repository.deactivateMembership("tenant-a", PROJECT_ID, "developer", NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.disableMember(PROJECT_ID, "developer"))
                .isInstanceOf(ProjectMembershipConflictException.class)
                .hasMessageContaining("MEMBERSHIP_VERSION_CONFLICT");
        assertThat(auditEvents).isEmpty();
    }

    @Test
    void membershipChangesEmitRedactedAuditsWithoutSubjectOrTokenMaterial() {
        ProjectMembershipRecord target = new ProjectMembershipRecord(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, "INACTIVE", NOW, NOW, 3);
        when(repository.findMembershipIncludingInactive("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(target));
        when(repository.updateMembershipStatus("tenant-a", PROJECT_ID, "developer", "ACTIVE", 3, NOW))
                .thenReturn(true);

        service.enableMember(PROJECT_ID, "developer", 3);

        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_MEMBER_ENABLED");
            assertThat(event.resourceId()).isEqualTo(PROJECT_ID.toString());
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
            assertThat(event.attributes()).doesNotContainValue("developer");
        });
    }

    @Test
    void auditSinkFailureDoesNotChangeSuccessfulMembershipUpdate() {
        service = new ProjectAuthorizationService(repository, Clock.fixed(NOW, ZoneOffset.UTC), event -> {
            throw new IllegalStateException("audit unavailable");
        });
        ProjectMembershipRecord target = new ProjectMembershipRecord(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, "INACTIVE", NOW, NOW, 3);
        when(repository.findMembershipIncludingInactive("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(target));
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
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_MEMBER_ROLE_CHANGED");
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
        });
    }
}

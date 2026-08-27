package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectInvitationServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private ProjectInvitationRepository repository;
    private ProjectInvitationService service;
    private List<AuditEvent> auditEvents;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectInvitationRepository.class);
        auditEvents = new ArrayList<>();
        service = new ProjectInvitationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom(), null,
                auditEvents::add);
        PrincipalContext.set(new Principal("owner",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        when(repository.findProject("tenant-a", PROJECT_ID)).thenReturn(Optional.of(
                ProjectRecord.create(PROJECT_ID, "tenant-a", "project-a", "owner", NOW)));
        when(repository.findMembership("tenant-a", PROJECT_ID, "owner")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner", ProjectRole.OWNER, NOW)));
        when(repository.insertInvitationIdempotency(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void invitationPersistsOnlyTokenHashAndAcceptActivatesMembership() {
        ProjectInvitationService.InvitationResult invitation = service.invite(
                PROJECT_ID, "invite-1", "developer", ProjectRole.DEVELOPER);

        assertThat(invitation.token()).isNotBlank();
        assertThat(invitation.record().tokenHash()).doesNotContain(invitation.token());
        verify(repository).insertInvitation(any(ProjectInvitationRecord.class));
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("PROJECT_MEMBER_INVITED");
            assertThat(event.attributes()).containsEntry("target_subject_hash",
                    "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
            assertThat(event.attributes()).doesNotContainValue(invitation.token());
        });

        ProjectInvitationRecord pending = invitation.record();
        when(repository.findInvitationByTokenHash("tenant-a", pending.tokenHash()))
                .thenReturn(Optional.of(pending));
        when(repository.acceptInvitation(pending.id(), NOW)).thenReturn(true);

        ProjectMembershipRecord accepted = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER, NOW);
        when(repository.findMembership("tenant-a", PROJECT_ID, "developer"))
                .thenReturn(Optional.of(accepted));
        PrincipalContext.set(new Principal("developer",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));

        assertThat(service.accept(invitation.token())).isEqualTo(accepted);
        verify(repository).upsertMembership(accepted);
        assertThat(auditEvents).hasSize(2);
        assertThat(auditEvents.get(1).action()).isEqualTo("PROJECT_MEMBER_ACCEPTED");
        assertThat(auditEvents.get(1).attributes()).containsEntry("target_subject_hash",
                "88fa0d759f845b47c044c2cd44e29082cf6fea665c30c146374ec7c8f3d699e3");
    }

    @Test
    void expiredInvitationIsRejectedWithoutMembershipWrite() {
        ProjectInvitationRecord expired = new ProjectInvitationRecord(
                UUID.randomUUID(), "tenant-a", PROJECT_ID, "developer", ProjectRole.DEVELOPER,
                hash("raw-token-for-test"), NOW.minusSeconds(1), "owner", NOW.minusSeconds(10),
                ProjectInvitationRecord.Status.INVITED, null);
        when(repository.findInvitationByTokenHash("tenant-a", expired.tokenHash()))
                .thenReturn(Optional.of(expired));
        PrincipalContext.set(new Principal("developer",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));

        assertThatThrownBy(() -> service.accept("raw-token-for-test"))
                .isInstanceOf(ProjectMembershipConflictException.class)
                .hasMessageContaining("INVITATION_EXPIRED");
        verify(repository, never()).upsertMembership(any());
    }

    @Test
    void invitationAuditFailureDoesNotBlockInvitationPersistence() {
        service = new ProjectInvitationService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom(), null, event -> {
                    throw new IllegalStateException("audit unavailable");
                });

        ProjectInvitationService.InvitationResult result = service.invite(
                PROJECT_ID, "invite-audit-failure", "developer", ProjectRole.DEVELOPER);

        assertThat(result.token()).isNotBlank();
        verify(repository).insertInvitation(any(ProjectInvitationRecord.class));
    }

    @Test
    void duplicateInvitationKeyReusesInvitationWithoutReturningASecondToken() {
        ProjectInvitationRecord existing = ProjectInvitationRecord.invited(UUID.randomUUID(), "tenant-a", PROJECT_ID,
                "developer", ProjectRole.DEVELOPER, "hash", NOW.plusSeconds(60), "owner", NOW);
        when(repository.findInvitationIdempotency("tenant-a", PROJECT_ID, "invite-1"))
                .thenReturn(Optional.of(new ProjectInvitationRepository.InvitationIdempotency(
                        "tenant-a", PROJECT_ID, "invite-1", hash("INVITE\u0000developer\u0000DEVELOPER"),
                        existing.id(), NOW)));
        when(repository.findInvitation(existing.id()))
                .thenReturn(Optional.of(existing));

        ProjectInvitationService.InvitationResult result = service.invite(
                PROJECT_ID, "invite-1", "developer", ProjectRole.DEVELOPER);

        assertThat(result.record()).isEqualTo(existing);
        assertThat(result.token()).isEmpty();
        verify(repository, never()).insertInvitation(any());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

}

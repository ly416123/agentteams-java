package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() {
        repository = mock(ProjectInvitationRepository.class);
        service = new ProjectInvitationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        PrincipalContext.set(new Principal("owner",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        when(repository.findProject("tenant-a", PROJECT_ID)).thenReturn(Optional.of(
                ProjectRecord.create(PROJECT_ID, "tenant-a", "project-a", "owner", NOW)));
        when(repository.findMembership("tenant-a", PROJECT_ID, "owner")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner", ProjectRole.OWNER, NOW)));
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

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}

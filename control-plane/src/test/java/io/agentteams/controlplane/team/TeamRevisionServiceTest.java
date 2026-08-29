package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;

class TeamRevisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("team:write"));
    private ResourceScopeRepository scopes;

    @BeforeEach
    void setPrincipal() {
        scopes = mock(ResourceScopeRepository.class);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void clearPrincipal() { PrincipalContext.clear(); }

    @Test
    void revisionMutationFailsClosedWithoutPrincipal() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        TeamRevisionService service = new TeamRevisionService(repository, repository::validatePublish, scopes);
        PrincipalContext.clear();

        assertThatThrownBy(() -> service.createDraft(UUID.randomUUID(), UUID.randomUUID(), "{}", List.of(),
                "alice", "key", NOW)).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("authentication");
        verify(repository, never()).createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void revisionMutationRejectsCrossTeamPrincipalBeforePersistence() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        ResourceScopeRepository scopes = mock(ResourceScopeRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID leader = UUID.randomUUID();
        PrincipalContext.set(PRINCIPAL);
        org.mockito.Mockito.doThrow(new AuthorizationException("resource is outside caller team"))
                .when(scopes).requireVisible("TEAM", teamId);
        TeamRevisionService service = new TeamRevisionService(repository, repository::validatePublish, scopes);

        assertThatThrownBy(() -> service.createDraft(teamId, leader, "{}", List.of(leader), "alice", "key", NOW))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("outside caller team");
        verify(repository, never()).createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publishedRevisionCannotBeModified() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        UUID teamId = UUID.randomUUID();
        TeamRevision published = revision(teamId, 3, TeamRevisionStatus.PUBLISHED, null);
        when(repository.find(teamId, 3)).thenReturn(Optional.of(published));
        TeamRevisionService service = new TeamRevisionService(repository, repository::validatePublish, scopes);

        assertThatThrownBy(() -> service.updateOverlay(teamId, 3, "{\"changed\":true}", 0, "alice", NOW))
                .isInstanceOf(TeamRevisionConflictException.class)
                .hasMessageContaining("immutable");
        verify(repository, never()).update(any());
    }

    @Test
    void rollbackCreatesNewRevisionInsteadOfMutatingStableRevision() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        UUID teamId = UUID.randomUUID();
        TeamRevision target = revision(teamId, 2, TeamRevisionStatus.PUBLISHED, null);
        when(repository.find(teamId, 2)).thenReturn(Optional.of(target));
        when(repository.createRollback(eq(teamId), eq(target), eq(0L), eq("alice"), eq(NOW), eq("rollback-key"),
                any(), any()))
                .thenReturn(new TeamRevision(teamId, 4, target.leaderAgentId(), target.overlayJson(), target.digest(),
                        TeamRevisionStatus.DRAFT, 2L, "alice", NOW, 0, target.memberAgentIds()));
        TeamRevisionService service = new TeamRevisionService(repository, repository::validatePublish, scopes);

        TeamRevision rollback = service.rollback(teamId, 2, 0, "alice", "rollback-key", NOW);

        assertThat(rollback.revision()).isEqualTo(4);
        assertThat(rollback.rollbackOfRevision()).isEqualTo(2L);
        assertThat(rollback.status()).isEqualTo(TeamRevisionStatus.DRAFT);
        assertThat(rollback.overlayJson()).isEqualTo(target.overlayJson());
        verify(repository).createRollback(eq(teamId), eq(target), eq(0L), eq("alice"), eq(NOW), eq("rollback-key"),
                any(), any());
    }

    @Test
    void revisionCopiesMembersAndNormalizesOverlay() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID leaderAgentId = UUID.randomUUID();
        when(repository.createDraft(eq(teamId), eq(leaderAgentId), eq("{\"a\":2,\"z\":1}"), any(),
                eq(null), eq("alice"), eq(NOW), eq(List.of(leaderAgentId)), eq("create-key")))
                .thenAnswer(invocation -> new TeamRevision(teamId, 1, leaderAgentId, invocation.getArgument(2),
                        invocation.getArgument(3, String.class), TeamRevisionStatus.DRAFT, null, "alice", NOW, 0,
                        List.of(leaderAgentId)));
        TeamRevisionService service = new TeamRevisionService(repository, repository::validatePublish, scopes);

        TeamRevision created = service.createDraft(teamId, leaderAgentId,
                "{\"z\":1,\"a\":2}", List.of(leaderAgentId), "alice", "create-key", NOW);

        assertThat(created.overlayJson()).isEqualTo("{\"a\":2,\"z\":1}");
        assertThat(created.digest()).hasSize(64);
        assertThat(created.status()).isEqualTo(TeamRevisionStatus.DRAFT);
    }

    @Test
    void publishDelegatesCasAndIdempotencyToOneRepositoryOperationAfterValidation() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevisionPublishValidator validator = mock(TeamRevisionPublishValidator.class);
        UUID teamId = UUID.randomUUID();
        TeamRevision draft = revision(teamId, 3, TeamRevisionStatus.DRAFT, null);
        TeamRevision published = new TeamRevision(teamId, 3, draft.leaderAgentId(), draft.overlayJson(), draft.digest(),
                TeamRevisionStatus.PUBLISHED, null, "alice", NOW, 1, draft.memberAgentIds());
        when(repository.find(teamId, 3)).thenReturn(Optional.of(draft));
        when(repository.publish(eq(teamId), eq(3L), eq(0L), eq("publish-key"), eq(validator), any()))
                .thenReturn(published);
        TeamRevisionService service = new TeamRevisionService(repository, validator, scopes);

        assertThat(service.publish(teamId, 3, 0, "publish-key")).isSameAs(published);

        verify(repository).publish(eq(teamId), eq(3L), eq(0L), eq("publish-key"), eq(validator), any());
        verify(validator, never()).validate(any());
        verify(repository, never()).deprecatePublished(any(), any(Long.class));
    }

    @Test
    void reviewPersistsItsIdempotencyKey() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevision draft = revision(UUID.randomUUID(), 1, TeamRevisionStatus.DRAFT, null);
        when(repository.find(draft.teamId(), 1)).thenReturn(Optional.of(draft));
        when(repository.transition(eq(draft.teamId()), eq(1L), eq(0L), eq(TeamRevisionStatus.DRAFT),
                eq(TeamRevisionStatus.REVIEWING), eq("review-key"), any())).thenReturn(draft);
        new TeamRevisionService(repository, repository::validatePublish, scopes).review(draft.teamId(), 1, 0, "review-key");
        verify(repository).transition(eq(draft.teamId()), eq(1L), eq(0L), eq(TeamRevisionStatus.DRAFT),
                eq(TeamRevisionStatus.REVIEWING), eq("review-key"), any());
    }

    @Test
    void updateOverlayRequiresIdempotencyKeyAndRequestHash() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevision draft = revision(UUID.randomUUID(), 1, TeamRevisionStatus.DRAFT, null);
        when(repository.find(draft.teamId(), 1)).thenReturn(Optional.of(draft));
        when(repository.update(any(), eq("overlay-key"), any())).thenReturn(draft);

        new TeamRevisionService(repository, repository::validatePublish, scopes).updateOverlay(draft.teamId(), 1, "{\"changed\":true}", 0,
                "alice", NOW, "overlay-key");

        verify(repository).update(any(), eq("overlay-key"), any());
    }

    @Test
    void publishPassesValidationIntoAtomicRepositoryOperation() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevisionPublishValidator validator = mock(TeamRevisionPublishValidator.class);
        TeamRevision draft = revision(UUID.randomUUID(), 3, TeamRevisionStatus.DRAFT, null);
        when(repository.find(draft.teamId(), 3)).thenReturn(Optional.of(draft));
        when(repository.publish(eq(draft.teamId()), eq(3L), eq(0L), eq("publish-key"), eq(validator), any()))
                .thenReturn(draft);

        new TeamRevisionService(repository, validator, scopes).publish(draft.teamId(), 3, 0, "publish-key");

        verify(repository).publish(eq(draft.teamId()), eq(3L), eq(0L), eq("publish-key"), eq(validator), any());
        verify(validator, never()).validate(any());
    }

    @Test
    void rollbackUsesTargetVersionCasAndAtomicValidation() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevisionPublishValidator validator = mock(TeamRevisionPublishValidator.class);
        TeamRevision target = revision(UUID.randomUUID(), 2, TeamRevisionStatus.PUBLISHED, null);
        when(repository.find(target.teamId(), 2)).thenReturn(Optional.of(target));
        when(repository.createRollback(eq(target.teamId()), eq(target), eq(0L), eq("alice"), eq(NOW),
                eq("rollback-key"), eq(validator), any())).thenReturn(target);

        new TeamRevisionService(repository, validator, scopes).rollback(target.teamId(), 2, 0, "alice",
                "rollback-key", NOW);

        verify(repository).createRollback(eq(target.teamId()), eq(target), eq(0L), eq("alice"), eq(NOW),
                eq("rollback-key"), eq(validator), any());
        verify(validator, never()).validate(any());
    }

    @Test
    void draftRejectsEmptyMembersBeforePersistence() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        assertThatThrownBy(() -> new TeamRevisionService(repository, repository::validatePublish, scopes).createDraft(UUID.randomUUID(), UUID.randomUUID(),
                "{}", List.of(), "alice", "key", NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("members");
        verify(repository, never()).createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static TeamRevision revision(UUID teamId, long revision, TeamRevisionStatus status,
            Long rollbackOfRevision) {
        UUID leader = UUID.randomUUID();
        return new TeamRevision(teamId, revision, leader, "{\"stable\":true}", "stable-digest", status,
                rollbackOfRevision, "alice", NOW, 0, List.of(leader));
    }
}

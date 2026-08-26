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
import org.junit.jupiter.api.Test;

class TeamRevisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void publishedRevisionCannotBeModified() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        UUID teamId = UUID.randomUUID();
        TeamRevision published = revision(teamId, 3, TeamRevisionStatus.PUBLISHED, null);
        when(repository.find(teamId, 3)).thenReturn(Optional.of(published));
        TeamRevisionService service = new TeamRevisionService(repository);

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
        when(repository.createRollback(eq(teamId), eq(target), eq("alice"), eq(NOW), eq("rollback-key")))
                .thenReturn(new TeamRevision(teamId, 4, target.leaderAgentId(), target.overlayJson(), target.digest(),
                        TeamRevisionStatus.DRAFT, 2L, "alice", NOW, 0, target.memberAgentIds()));
        TeamRevisionService service = new TeamRevisionService(repository);

        TeamRevision rollback = service.rollback(teamId, 2, "alice", "rollback-key", NOW);

        assertThat(rollback.revision()).isEqualTo(4);
        assertThat(rollback.rollbackOfRevision()).isEqualTo(2L);
        assertThat(rollback.status()).isEqualTo(TeamRevisionStatus.DRAFT);
        assertThat(rollback.overlayJson()).isEqualTo(target.overlayJson());
        verify(repository).createRollback(teamId, target, "alice", NOW, "rollback-key");
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
        TeamRevisionService service = new TeamRevisionService(repository);

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
        when(repository.publish(teamId, 3, 0, "publish-key")).thenReturn(published);
        TeamRevisionService service = new TeamRevisionService(repository, validator);

        assertThat(service.publish(teamId, 3, 0, "publish-key")).isSameAs(published);

        var order = inOrder(validator, repository);
        order.verify(validator).validate(draft);
        order.verify(repository).publish(teamId, 3, 0, "publish-key");
        verify(repository, never()).deprecatePublished(any(), any(Long.class));
    }

    @Test
    void reviewPersistsItsIdempotencyKey() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        TeamRevision draft = revision(UUID.randomUUID(), 1, TeamRevisionStatus.DRAFT, null);
        when(repository.find(draft.teamId(), 1)).thenReturn(Optional.of(draft));
        when(repository.transition(draft.teamId(), 1, 0, TeamRevisionStatus.DRAFT,
                TeamRevisionStatus.REVIEWING, "review-key")).thenReturn(draft);
        new TeamRevisionService(repository).review(draft.teamId(), 1, 0, "review-key");
        verify(repository).transition(draft.teamId(), 1, 0, TeamRevisionStatus.DRAFT,
                TeamRevisionStatus.REVIEWING, "review-key");
    }

    private static TeamRevision revision(UUID teamId, long revision, TeamRevisionStatus status,
            Long rollbackOfRevision) {
        UUID leader = UUID.randomUUID();
        return new TeamRevision(teamId, revision, leader, "{\"stable\":true}", "stable-digest", status,
                rollbackOfRevision, "alice", NOW, 0, List.of(leader));
    }
}

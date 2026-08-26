package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(repository.nextRevision(teamId)).thenReturn(4L);
        when(repository.insert(any(), eq("rollback-key"))).thenAnswer(invocation -> invocation.getArgument(0));
        TeamRevisionService service = new TeamRevisionService(repository);

        TeamRevision rollback = service.rollback(teamId, 2, "alice", "rollback-key", NOW);

        assertThat(rollback.revision()).isEqualTo(4);
        assertThat(rollback.rollbackOfRevision()).isEqualTo(2L);
        assertThat(rollback.status()).isEqualTo(TeamRevisionStatus.DRAFT);
        assertThat(rollback.overlayJson()).isEqualTo(target.overlayJson());
        verify(repository).insert(any(), eq("rollback-key"));
    }

    @Test
    void revisionCopiesMembersAndNormalizesOverlay() {
        TeamRevisionRepository repository = mock(TeamRevisionRepository.class);
        UUID teamId = UUID.randomUUID();
        when(repository.nextRevision(teamId)).thenReturn(1L);
        when(repository.insert(any(), eq("create-key"))).thenAnswer(invocation -> invocation.getArgument(0));
        TeamRevisionService service = new TeamRevisionService(repository);
        UUID leaderAgentId = UUID.randomUUID();

        TeamRevision created = service.createDraft(teamId, leaderAgentId,
                "{\"z\":1,\"a\":2}", List.of(leaderAgentId), "alice", "create-key", NOW);

        assertThat(created.overlayJson()).isEqualTo("{\"a\":2,\"z\":1}");
        assertThat(created.digest()).hasSize(64);
        assertThat(created.status()).isEqualTo(TeamRevisionStatus.DRAFT);
    }

    private static TeamRevision revision(UUID teamId, long revision, TeamRevisionStatus status,
            Long rollbackOfRevision) {
        UUID leader = UUID.randomUUID();
        return new TeamRevision(teamId, revision, leader, "{\"stable\":true}", "stable-digest", status,
                rollbackOfRevision, "alice", NOW, 0, List.of(leader));
    }
}

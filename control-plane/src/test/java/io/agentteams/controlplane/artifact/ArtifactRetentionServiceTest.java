package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.storage.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final ArtifactRetentionPolicy POLICY = new ArtifactRetentionPolicy(
            Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(2), false);

    @Test
    void tombstonesExpiredArtifactBeforeDeletingObject() {
        ArtifactRetentionRepository repository = mock(ArtifactRetentionRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID artifactId = UUID.randomUUID();
        ArtifactRetentionCandidate candidate = candidate(artifactId, false);
        ArtifactRetentionTombstone tombstone = tombstone(artifactId, "PENDING");
        when(repository.findExpiredCandidates(NOW, POLICY, 10)).thenReturn(List.of(candidate));
        when(repository.insertTombstone(eq(candidate), any(), eq(NOW), eq("PENDING"), any(), any())).thenReturn(true);
        when(repository.findDueTombstones(NOW, 10)).thenReturn(List.of(tombstone));

        ArtifactRetentionService service = service(repository, storage);

        assertThat(service.cleanup(POLICY, 10).deleted()).isEqualTo(1);
        verify(storage).delete(candidate.storageKey());
        verify(repository).markDeleted(tombstone.id(), artifactId, NOW);
    }

    @Test
    void preservesFailedDeletionAsRetryableTombstone() {
        ArtifactRetentionRepository repository = mock(ArtifactRetentionRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID artifactId = UUID.randomUUID();
        ArtifactRetentionCandidate candidate = candidate(artifactId, false);
        ArtifactRetentionTombstone tombstone = tombstone(artifactId, "PENDING");
        when(repository.findExpiredCandidates(NOW, POLICY, 10)).thenReturn(List.of(candidate));
        when(repository.insertTombstone(eq(candidate), any(), eq(NOW), eq("PENDING"), any(), any())).thenReturn(true);
        when(repository.findDueTombstones(NOW, 10)).thenReturn(List.of(tombstone));
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(candidate.storageKey());

        var result = service(repository, storage).cleanup(POLICY, 10);

        assertThat(result.failed()).isEqualTo(1);
        verify(repository).markFailed(eq(tombstone.id()), eq(NOW), any(), eq("storage unavailable"));
    }

    @Test
    void legalHoldCreatesHeldTombstoneAndNeverDeletesObject() {
        ArtifactRetentionRepository repository = mock(ArtifactRetentionRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID artifactId = UUID.randomUUID();
        ArtifactRetentionCandidate candidate = candidate(artifactId, true);
        when(repository.findExpiredCandidates(NOW, POLICY, 10)).thenReturn(List.of(candidate));
        when(repository.insertTombstone(eq(candidate), any(), eq(NOW), eq("HELD"), any(), any())).thenReturn(true);
        when(repository.findDueTombstones(NOW, 10)).thenReturn(List.of());

        assertThat(service(repository, storage).cleanup(POLICY, 10).held()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    private static ArtifactRetentionService service(ArtifactRetentionRepository repository, ObjectStorage storage) {
        return new ArtifactRetentionService(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ArtifactRetentionCandidate candidate(UUID artifactId, boolean legalHold) {
        return new ArtifactRetentionCandidate(artifactId, UUID.randomUUID(), "tasks/a/result.json",
                NOW.minus(Duration.ofDays(31)), new ArtifactRetentionPolicy(
                        Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(2), legalHold),
                0, "PROJECT");
    }

    private static ArtifactRetentionTombstone tombstone(UUID artifactId, String status) {
        return new ArtifactRetentionTombstone(UUID.randomUUID(), artifactId, UUID.randomUUID(),
                "tasks/a/result.json", status, false, 0, NOW, "test-operator", 0);
    }
}

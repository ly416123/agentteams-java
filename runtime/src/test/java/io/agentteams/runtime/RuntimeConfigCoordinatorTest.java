package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeConfigCoordinatorTest {
    @Test
    void snapshotsAreImmutableAndStagingDoesNotChangeTheActiveVersion() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(1, "sha-1", Map.of("model", "deepseek"));

        RuntimeConfigStage stage = coordinator.stage(snapshot);

        assertThat(stage.snapshot()).isEqualTo(snapshot);
        assertThat(coordinator.activeSnapshot()).isEmpty();
        assertThat(applier.stagedSnapshots()).containsExactly(snapshot);
        assertThatThrownBy(() -> stage.snapshot().values().put("model", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void successfulActivationPublishesTheNewVersionAtomically() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot snapshot = snapshot(1, "sha-1");

        RuntimeConfigApplyResult result = coordinator.apply(snapshot);

        assertThat(result.status()).isEqualTo(RuntimeConfigApplyResult.Status.ACTIVATED);
        assertThat(result.activeSnapshot()).isEqualTo(snapshot);
        assertThat(coordinator.activeSnapshot()).contains(snapshot);
        assertThat(applier.activeSnapshot()).contains(snapshot);
        assertThat(applier.discarded()).isZero();
    }

    @Test
    void activationFailureDiscardsStagingAndRetainsThePreviousActiveVersion() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot previous = snapshot(1, "sha-1");
        RuntimeConfigSnapshot next = snapshot(2, "sha-2");
        coordinator.apply(previous);
        applier.failActivation = true;

        assertThatThrownBy(() -> coordinator.apply(next))
                .isInstanceOf(RuntimeConfigApplyException.class)
                .hasMessageContaining("activation");

        assertThat(coordinator.activeSnapshot()).contains(previous);
        assertThat(applier.activeSnapshot()).contains(previous);
        assertThat(applier.discarded()).isEqualTo(1);
    }

    @Test
    void failedStagingLeavesThePreviousActiveVersionUntouched() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot previous = snapshot(1, "sha-1");
        coordinator.apply(previous);
        applier.failStaging = true;

        assertThatThrownBy(() -> coordinator.apply(snapshot(2, "sha-2")))
                .isInstanceOf(RuntimeConfigApplyException.class)
                .hasMessageContaining("staging");

        assertThat(coordinator.activeSnapshot()).contains(previous);
        assertThat(applier.activeSnapshot()).contains(previous);
        assertThat(applier.discarded()).isZero();
    }

    @Test
    void explicitDiscardReleasesStagingWithoutChangingTheActiveVersion() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot previous = snapshot(1, "sha-1");
        RuntimeConfigSnapshot next = snapshot(2, "sha-2");
        coordinator.apply(previous);
        RuntimeConfigStage stage = coordinator.stage(next);

        coordinator.discard(stage);

        assertThat(coordinator.activeSnapshot()).contains(previous);
        assertThat(applier.activeSnapshot()).contains(previous);
        assertThat(applier.discarded()).isEqualTo(1);
        assertThatThrownBy(() -> coordinator.activate(stage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");
    }

    @Test
    void sameVersionAndChecksumIsIdempotentButAConflictingVersionIsRejected() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot first = snapshot(1, "sha-1");
        coordinator.apply(first);

        RuntimeConfigApplyResult repeated = coordinator.apply(first);

        assertThat(repeated.status()).isEqualTo(RuntimeConfigApplyResult.Status.ALREADY_ACTIVE);
        assertThat(applier.stagedSnapshots()).containsExactly(first);
        assertThatThrownBy(() -> coordinator.apply(snapshot(1, "sha-other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> coordinator.apply(snapshot(0, "sha-0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollbackActivatesAnOlderImmutableSnapshotAndKeepsFutureVersionsValid() {
        RecordingApplier applier = new RecordingApplier();
        RuntimeConfigCoordinator coordinator = new RuntimeConfigCoordinator(applier);
        RuntimeConfigSnapshot first = snapshot(1, "sha-1");
        RuntimeConfigSnapshot second = snapshot(2, "sha-2");
        coordinator.apply(first);
        coordinator.apply(second);

        assertThat(coordinator.apply(first, true).activeSnapshot()).isEqualTo(first);
        assertThat(coordinator.activeSnapshot()).contains(first);
        assertThat(coordinator.apply(snapshot(3, "sha-3")).activeSnapshot().version()).isEqualTo(3);
    }

    private static RuntimeConfigSnapshot snapshot(long version, String checksum) {
        return new RuntimeConfigSnapshot(version, checksum, Map.of("version", Long.toString(version)));
    }

    private static final class RecordingApplier implements RuntimeConfigApplier {
        private final List<RuntimeConfigSnapshot> staged = new ArrayList<>();
        private RuntimeConfigSnapshot active;
        private boolean failStaging;
        private boolean failActivation;
        private int discarded;

        @Override
        public RuntimeConfigPrepared stage(RuntimeConfigSnapshot snapshot,
                Optional<RuntimeConfigSnapshot> current) {
            if (failStaging) {
                throw new IllegalStateException("cannot prepare config");
            }
            staged.add(snapshot);
            return new RuntimeConfigPrepared() {
                @Override
                public void activate() {
                    if (failActivation) {
                        throw new IllegalStateException("cannot activate config");
                    }
                    active = snapshot;
                }

                @Override
                public void discard() {
                    discarded++;
                }
            };
        }

        List<RuntimeConfigSnapshot> stagedSnapshots() {
            return staged;
        }

        Optional<RuntimeConfigSnapshot> activeSnapshot() {
            return Optional.ofNullable(active);
        }

        int discarded() {
            return discarded;
        }
    }
}

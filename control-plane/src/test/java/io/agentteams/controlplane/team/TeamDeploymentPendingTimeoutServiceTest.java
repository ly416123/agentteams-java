package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamDeploymentPendingTimeoutServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void failsStaleMembersUsingTheAgreedCutoffAndBatchSize() {
        RecordingRepository repository = new RecordingRepository();
        repository.nextResult = 3;
        repository.nextRepaired = 2;

        assertThat(new TeamDeploymentPendingTimeoutService(repository)
                .reconcile(NOW, PENDING_TIMEOUT, 100))
                .isEqualTo(new TeamDeploymentPendingTimeoutService.TimeoutResult(3, 2));
        assertThat(repository.cutoff).isEqualTo(NOW.minus(PENDING_TIMEOUT));
        assertThat(repository.now).isEqualTo(NOW);
        assertThat(repository.limit).isEqualTo(100);
        assertThat(repository.repairedLimit).isEqualTo(100);
    }

    @Test
    void survivesARepositoryFailureByReportingZeroAndLettingTheNextRunRetry() {
        RecordingRepository repository = new RecordingRepository();
        repository.fail = true;

        assertThat(new TeamDeploymentPendingTimeoutService(repository)
                .reconcile(NOW, PENDING_TIMEOUT, 100))
                .isEqualTo(new TeamDeploymentPendingTimeoutService.TimeoutResult(0, 0));
    }

    @Test
    void rejectsANonPositiveTimeout() {
        TeamDeploymentPendingTimeoutService service =
                new TeamDeploymentPendingTimeoutService(new RecordingRepository());

        assertThatThrownBy(() -> service.reconcile(NOW, Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingTimeout");
    }

    @Test
    void rejectsABatchSizeOutsideTheAllowedRange() {
        TeamDeploymentPendingTimeoutService service =
                new TeamDeploymentPendingTimeoutService(new RecordingRepository());

        assertThatThrownBy(() -> service.reconcile(NOW, PENDING_TIMEOUT, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    private static final class RecordingRepository implements TeamDeploymentPendingTimeoutRepository {
        private int nextResult;
        private int nextRepaired;
        private boolean fail;
        private Instant now;
        private Instant cutoff;
        private int limit;
        private int repairedLimit;
        private final List<Instant> calls = new ArrayList<>();

        @Override
        public int failStalePendingMembers(Instant now, Instant applyUpdatedBefore, int limit) {
            this.calls.add(now);
            if (fail) throw new IllegalStateException("database unavailable");
            this.now = now;
            this.cutoff = applyUpdatedBefore;
            this.limit = limit;
            return nextResult;
        }

        @Override
        public int refreshPendingAggregates(int limit) {
            if (fail) throw new IllegalStateException("database unavailable");
            this.repairedLimit = limit;
            return nextRepaired;
        }
    }
}

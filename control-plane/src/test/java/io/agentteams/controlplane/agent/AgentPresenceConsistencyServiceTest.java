package io.agentteams.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.agent.AgentPresenceConsistencyService.ReconcileResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPresenceConsistencyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @Test
    void downgradesEveryStaleAgentWithinTheAgreedWindow() {
        RecordingRepository repository = new RecordingRepository();
        repository.stale = List.of(UUID.randomUUID(), UUID.randomUUID());
        AgentPresenceConsistencyService service = new AgentPresenceConsistencyService(repository);

        assertThat(service.reconcile(NOW, STALE_AFTER, 100))
                .isEqualTo(new ReconcileResult(2, 2, 0));
        assertThat(repository.cutoff).isEqualTo(NOW.minus(STALE_AFTER));
        assertThat(repository.limit).isEqualTo(100);
        assertThat(repository.attempted).containsExactlyElementsOf(repository.stale);
    }

    @Test
    void keepsScanningAfterOneAgentCannotBeDowngraded() {
        UUID first = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        RecordingRepository repository = new RecordingRepository();
        repository.stale = List.of(first, broken, last);
        repository.failFor = broken;

        assertThat(new AgentPresenceConsistencyService(repository).reconcile(NOW, STALE_AFTER, 100))
                .isEqualTo(new ReconcileResult(3, 2, 1));
        assertThat(repository.attempted).containsExactly(first, broken, last);
    }

    @Test
    void rejectsANonPositiveStaleWindow() {
        AgentPresenceConsistencyService service = new AgentPresenceConsistencyService(new RecordingRepository());

        assertThatThrownBy(() -> service.reconcile(NOW, Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staleAfter");
    }

    @Test
    void rejectsABatchSizeOutsideTheAllowedRange() {
        AgentPresenceConsistencyService service = new AgentPresenceConsistencyService(new RecordingRepository());

        assertThatThrownBy(() -> service.reconcile(NOW, STALE_AFTER, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> service.reconcile(NOW, STALE_AFTER, 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    private static final class RecordingRepository implements AgentPresenceConsistencyRepository {
        private List<UUID> stale = List.of();
        private UUID failFor;
        private Instant cutoff;
        private int limit;
        private final List<UUID> attempted = new ArrayList<>();

        @Override
        public List<UUID> findStaleReadyAgents(Instant lastSeenBefore, int limit) {
            this.cutoff = lastSeenBefore;
            this.limit = limit;
            return stale;
        }

        @Override
        public int markOffline(UUID agentId, Instant at) {
            attempted.add(agentId);
            if (agentId.equals(failFor)) {
                throw new IllegalStateException("agent disappeared");
            }
            return 1;
        }
    }
}

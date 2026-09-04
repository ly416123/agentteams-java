package io.agentteams.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.agent.AgentPresenceConsistencyJob.RunResult;
import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AgentPresenceConsistencyJobTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @Test
    void downgradesOnlyWhenThisReplicaOwnsTheLease() {
        UUID agentId = UUID.randomUUID();
        MutableRepository repository = new MutableRepository(List.of(agentId));
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("agent-presence-consistency", "pod-a", NOW, LEASE_DURATION)).thenReturn(true);
        AgentPresenceConsistencyJob job = job(repository, new SchedulerLeaseService(leases));

        assertThat(job.runOnce()).isEqualTo(new RunResult(true, 1, 1, 0));
        assertThat(repository.attempted).containsExactly(agentId);
        assertThat(repository.cutoff).isEqualTo(NOW.minus(STALE_AFTER));
        verify(leases).release("agent-presence-consistency", "pod-a", NOW);
    }

    @Test
    void ignoresTheScanWhileAnotherReplicaOwnsTheLease() {
        SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
        when(leases.run(eq("agent-presence-consistency"), eq("pod-a"), eq(NOW), eq(LEASE_DURATION),
                any(Supplier.class))).thenReturn(new SchedulerLeaseService.Result<>(false, null));
        AgentPresenceConsistencyJob job = job(new MutableRepository(List.of()), leases);

        assertThat(job.runOnce()).isEqualTo(new RunResult(false, 0, 0, 0));
    }

    private AgentPresenceConsistencyJob job(AgentPresenceConsistencyRepository repository,
            SchedulerLeaseService leases) {
        return new AgentPresenceConsistencyJob(new AgentPresenceConsistencyService(repository), leases,
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-a", LEASE_DURATION, STALE_AFTER, 100);
    }

    /** Fails loudly whenever the reconcile body runs, so lease gating is observable. */
    private static final class MutableRepository implements AgentPresenceConsistencyRepository {
        private final List<UUID> stale;
        private final List<UUID> attempted = new ArrayList<>();
        private Instant cutoff;

        private MutableRepository(List<UUID> stale) {
            this.stale = stale;
        }

        @Override
        public List<UUID> findStaleReadyAgents(Instant lastSeenBefore, int limit) {
            if (stale.isEmpty()) {
                throw new AssertionError("the scan must not touch the database");
            }
            this.cutoff = lastSeenBefore;
            return stale;
        }

        @Override
        public int markOffline(UUID agentId, Instant at) {
            attempted.add(agentId);
            return 1;
        }
    }
}

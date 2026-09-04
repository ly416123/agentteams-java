package io.agentteams.controlplane.agent;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only periodic reconciliation of agent liveness against the gateway presence projection. */
public final class AgentPresenceConsistencyJob {
    private final AgentPresenceConsistencyService service;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final Duration staleAfter;
    private final int batchSize;

    public AgentPresenceConsistencyJob(AgentPresenceConsistencyService service, SchedulerLeaseService lease,
            Clock clock, String owner, Duration leaseDuration, Duration staleAfter, int batchSize) {
        this.service = Objects.requireNonNull(service, "service");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "agent-presence-consistency" : owner.trim();
        this.leaseDuration = leaseDuration;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.agent-presence-consistency.reconcile-interval-ms:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<AgentPresenceConsistencyService.ReconcileResult> result = lease.run(
                "agent-presence-consistency", owner, now, leaseDuration,
                () -> service.reconcile(now, staleAfter, batchSize));
        if (!result.leader()) return new RunResult(false, 0, 0, 0);
        AgentPresenceConsistencyService.ReconcileResult value = result.value();
        return new RunResult(true, value.scanned(), value.repaired(), value.failures());
    }

    public record RunResult(boolean leader, int scanned, int repaired, int failures) {
        public RunResult {
            if (scanned < 0 || repaired < 0 || failures < 0) {
                throw new IllegalArgumentException("reconcile counts must not be negative");
            }
        }
    }
}

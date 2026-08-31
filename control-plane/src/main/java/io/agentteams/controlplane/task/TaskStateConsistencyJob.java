package io.agentteams.controlplane.task;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only periodic task/run consistency reconciliation. */
public final class TaskStateConsistencyJob {
    private final TaskStateConsistencyService service;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final Duration lookback;
    private final int batchSize;

    public TaskStateConsistencyJob(TaskStateConsistencyService service, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, Duration lookback, int batchSize) {
        this.service = Objects.requireNonNull(service, "service");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "task-state-consistency" : owner.trim();
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.lookback = requirePositive(lookback, "lookback");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.task-state-consistency.reconcile-interval-ms:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<TaskStateConsistencyService.ReconcileResult> result = lease.run(
                "task-state-consistency", owner, now, leaseDuration,
                () -> service.reconcile(now, lookback, batchSize));
        if (!result.leader()) return new RunResult(false, 0, 0, 0, 0);
        TaskStateConsistencyService.ReconcileResult value = result.value();
        return new RunResult(true, value.scanned(), value.issues(), value.resolved(), value.failures());
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public record RunResult(boolean leader, int scanned, int issues, int resolved, int failures) {
        public RunResult {
            if (scanned < 0 || issues < 0 || resolved < 0 || failures < 0) {
                throw new IllegalArgumentException("reconcile counts must not be negative");
            }
        }
    }
}

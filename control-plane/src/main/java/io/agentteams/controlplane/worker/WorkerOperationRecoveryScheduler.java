package io.agentteams.controlplane.worker;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Recovers expired Worker Operations independently of task traffic or a prior process. */
public final class WorkerOperationRecoveryScheduler {

    private final FoundationPersistenceService persistence;
    private final SchedulerLeaseService schedulerLease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;

    public WorkerOperationRecoveryScheduler(FoundationPersistenceService persistence,
            SchedulerLeaseService schedulerLease, Clock clock, String owner, Duration leaseDuration) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.schedulerLease = Objects.requireNonNull(schedulerLease, "schedulerLease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = requireText(owner);
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    @Scheduled(fixedDelayString = "${agentteams.worker-operations.scheduler.poll-interval-ms:1000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        return schedulerLease.run("worker-operation-recovery", owner, now, leaseDuration,
                () -> new RunResult(persistence.inTransaction(
                        tx -> WorkerOperationService.recoverExpiredOperations(tx, now))))
                .valueOr(new RunResult(0));
    }

    public record RunResult(int recoveredOperations) {
        public RunResult {
            if (recoveredOperations < 0) {
                throw new IllegalArgumentException("recoveredOperations must not be negative");
            }
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        return value.trim();
    }
}

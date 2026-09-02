package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Single-active scheduler that assigns queued tasks and repairs expired leases after restart. */
public final class TaskAssignmentScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAssignmentScheduler.class);

    private final TaskAssignmentService assignments;
    private final SchedulerLeaseService schedulerLease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;

    public TaskAssignmentScheduler(TaskAssignmentService assignments, SchedulerLeaseService schedulerLease,
            Clock clock, String owner, Duration leaseDuration, int batchSize) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.schedulerLease = Objects.requireNonNull(schedulerLease, "schedulerLease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = requireText(owner, "owner");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        this.batchSize = batchSize;
        LOGGER.info("Task assignment scheduler enabled owner={} leaseDuration={} batchSize={}",
                owner, leaseDuration, batchSize);
    }

    @Scheduled(fixedDelayString = "${agentteams.scheduler.poll-interval-ms:1000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        return schedulerLease.run("task-assignment", owner, now, leaseDuration, () -> {
            int recovered = assignments.recoverExpiredLeases(now);
            int assigned = 0;
            int unavailable = 0;
            for (UUID taskId : assignments.queuedTaskIds(batchSize, now)) {
                try {
                    assignments.queueReadyTask(taskId, clock.instant());
                    assigned++;
                } catch (IllegalStateException noCapacity) {
                    unavailable++;
                    LOGGER.debug("Task remains queued because no eligible agent is available taskId={}", taskId);
                } catch (RuntimeException invalidTask) {
                    unavailable++;
                    LOGGER.warn("Task could not be assigned and remains queued taskId={}", taskId,
                            invalidTask);
                }
            }
            return new RunResult(recovered, assigned, unavailable);
        }).valueOr(new RunResult(0, 0, 0));
    }

    public String owner() { return owner; }

    public record RunResult(int recoveredLeases, int assignedTasks, int unavailableTasks) {
        public RunResult {
            if (recoveredLeases < 0 || assignedTasks < 0 || unavailableTasks < 0) {
                throw new IllegalArgumentException("scheduler counts must not be negative");
            }
        }
    }

    public static String defaultOwner(String configured) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception ignored) {
            return "control-plane-" + UUID.randomUUID();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

package io.agentteams.controlplane.taskfile;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * G05 交付对账：AVAILABLE 的 OUTPUT 账本行经 storage.exists() 探测，缺失标记
 * MISSING（清单可见，不删记录）。多副本经 SchedulerLeaseService 租约单跑，
 * 仿 ArtifactRetentionCleanupJob（规格 §5.3）。
 */
public final class TaskFileReconciliationJob {

    private final TaskFileService files;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;

    public TaskFileReconciliationJob(TaskFileService files, SchedulerLeaseService lease,
            Clock clock, String owner, Duration leaseDuration, int batchSize) {
        this.files = Objects.requireNonNull(files, "files");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "task-file-reconciliation" : owner.trim();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.task-file.reconciliation-interval-ms:300000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<Integer> result = lease.run("task-file-reconciliation",
                owner, now, leaseDuration, () -> files.reconcileBatch(batchSize));
        return result.leader() ? new RunResult(true, result.value()) : new RunResult(false, 0);
    }

    public record RunResult(boolean leader, int markedMissing) {
    }
}

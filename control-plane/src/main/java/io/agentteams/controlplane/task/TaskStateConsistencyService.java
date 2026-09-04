package io.agentteams.controlplane.task;

import io.agentteams.observability.TaskMetricsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads task facts, records drift, and resolves findings that disappear. */
public final class TaskStateConsistencyService {
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Logger log = LoggerFactory.getLogger(TaskStateConsistencyService.class);

    private final TaskStateConsistencyRepository repository;
    private final TaskStateConsistencyChecker checker;
    private final TaskMetricsPort metrics;

    public TaskStateConsistencyService(TaskStateConsistencyRepository repository,
            TaskStateConsistencyChecker checker) {
        this(repository, checker, TaskMetricsPort.noop());
    }

    public TaskStateConsistencyService(TaskStateConsistencyRepository repository,
            TaskStateConsistencyChecker checker, TaskMetricsPort metrics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ReconcileResult reconcile(Instant now, Duration lookback, int batchSize) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lookback, "lookback");
        if (lookback.isNegative() || lookback.isZero()) throw new IllegalArgumentException("lookback must be positive");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<TaskStateConsistencySnapshot> snapshots = repository.findSnapshots(now.minus(lookback), batchSize);
        int issues = 0;
        int resolved = 0;
        int failures = 0;
        for (TaskStateConsistencySnapshot snapshot : snapshots) {
            try {
                List<TaskStateConsistencyIssue> current = checker.check(snapshot);
                Set<String> currentTypes = new HashSet<>();
                for (TaskStateConsistencyIssue issue : current) {
                    repository.upsertIssue(issue, now);
                    currentTypes.add(issue.type());
                    issues++;
                    metrics.taskConsistencyIssue();
                }
                for (String type : repository.findOpenIssueTypes(snapshot.taskId(), snapshot.runId())) {
                    if (!currentTypes.contains(type)) {
                        repository.resolveIssue(snapshot.taskId(), snapshot.runId(), type, now);
                        resolved++;
                        metrics.taskConsistencyResolved();
                    }
                }
            } catch (RuntimeException failure) {
                failures++;
                metrics.taskConsistencyScanFailed();
                log.warn("Task state consistency scan failed taskId={} runId={} errorType={}",
                        snapshot.taskId(), snapshot.runId(), failure.getClass().getSimpleName());
            }
        }
        return new ReconcileResult(snapshots.size(), issues, resolved, failures);
    }

    public List<TaskStateConsistencyIssueRecord> findOpenIssues(int limit) {
        return repository.findOpenIssues(limit);
    }

    public record ReconcileResult(int scanned, int issues, int resolved, int failures) {
        public ReconcileResult {
            if (scanned < 0 || issues < 0 || resolved < 0 || failures < 0) {
                throw new IllegalArgumentException("reconcile counts must not be negative");
            }
        }
    }
}

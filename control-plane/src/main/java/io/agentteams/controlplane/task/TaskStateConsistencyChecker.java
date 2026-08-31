package io.agentteams.controlplane.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure rule checker; it never changes Task or Run state. */
public final class TaskStateConsistencyChecker {
    public List<TaskStateConsistencyIssue> check(TaskStateConsistencySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<TaskStateConsistencyIssue> issues = new ArrayList<>();
        if (!allowedRunStatus(snapshot.taskPhase(), snapshot.runStatus())) {
            issues.add(issue(snapshot, "TASK_RUN_STATUS_MISMATCH",
                    "task phase " + snapshot.taskPhase() + " is incompatible with run status " + snapshot.runStatus()));
        }
        if (terminal(snapshot.taskPhase()) && snapshot.activeAttemptCount() > 0) {
            issues.add(issue(snapshot, "TERMINAL_ATTEMPT_ACTIVE", "terminal task has an active attempt"));
        }
        if (terminal(snapshot.taskPhase()) && snapshot.activeLeaseCount() > 0) {
            issues.add(issue(snapshot, "TERMINAL_LEASE_ACTIVE", "terminal task has an active lease"));
        }
        if (requiresManifest(snapshot.taskPhase(), snapshot.runStatus()) && snapshot.manifestStatus() == null) {
            issues.add(issue(snapshot, "RESULT_MANIFEST_MISSING", "terminal run has no result manifest"));
        } else if (snapshot.manifestStatus() != null && terminal(snapshot.runStatus())
                && !snapshot.runStatus().equals(snapshot.manifestStatus())) {
            issues.add(issue(snapshot, "RESULT_MANIFEST_STATUS_MISMATCH",
                    "run status " + snapshot.runStatus() + " differs from manifest status " + snapshot.manifestStatus()));
        }
        if ((snapshot.processEventCount() == 0 && snapshot.maxProcessSequence() != -1)
                || (snapshot.processEventCount() > 0
                        && snapshot.maxProcessSequence() + 1 != snapshot.processEventCount())) {
            issues.add(issue(snapshot, "PROCESS_SEQUENCE_GAP", "process event sequence is not contiguous from zero"));
        }
        if (terminal(snapshot.taskPhase()) && snapshot.unfinishedSubtaskCount() > 0) {
            issues.add(issue(snapshot, "TERMINAL_SUBTASK_INCOMPLETE", "terminal task has unfinished subtasks"));
        }
        return List.copyOf(issues);
    }

    private static TaskStateConsistencyIssue issue(TaskStateConsistencySnapshot snapshot, String type, String detail) {
        return new TaskStateConsistencyIssue(snapshot.taskId(), snapshot.runId(), snapshot.organizationId(),
                snapshot.tenantId(), type, snapshot.taskPhase(), snapshot.runStatus(), snapshot.manifestStatus(),
                detail, snapshot.observedAt());
    }

    private static boolean allowedRunStatus(String taskPhase, String runStatus) {
        return switch (taskPhase) {
            case "DRAFT", "QUEUED", "PAUSED" -> "QUEUED".equals(runStatus);
            case "ASSIGNED", "ACCEPTED", "RUNNING" -> "QUEUED".equals(runStatus) || "RUNNING".equals(runStatus);
            case "SUCCEEDED" -> "SUCCEEDED".equals(runStatus);
            case "FAILED" -> "FAILED".equals(runStatus);
            case "CANCELLED", "REJECTED" -> "CANCELLED".equals(runStatus);
            default -> false;
        };
    }

    private static boolean requiresManifest(String taskPhase, String runStatus) {
        return "SUCCEEDED".equals(taskPhase) || "FAILED".equals(taskPhase)
                || "SUCCEEDED".equals(runStatus) || "FAILED".equals(runStatus);
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "REJECTED".equals(status);
    }
}

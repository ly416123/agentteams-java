package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskStateConsistencyCheckerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private final TaskStateConsistencyChecker checker = new TaskStateConsistencyChecker();

    @Test
    void detectsTerminalRunDriftAndMissingTerminalEvidence() {
        List<TaskStateConsistencyIssue> issues = checker.check(snapshot(
                "SUCCEEDED", "RUNNING", null, 1, 0, -1, 1));

        assertThat(issues).extracting(TaskStateConsistencyIssue::type)
                .containsExactly("TASK_RUN_STATUS_MISMATCH", "TERMINAL_ATTEMPT_ACTIVE", "RESULT_MANIFEST_MISSING",
                        "TERMINAL_SUBTASK_INCOMPLETE");
    }

    @Test
    void acceptsCancelledRunWithoutManifestAndDetectsProcessSequenceGap() {
        assertThat(checker.check(snapshot("CANCELLED", "CANCELLED", null, 0, 3, 1, 0)))
                .extracting(TaskStateConsistencyIssue::type)
                .containsExactly("PROCESS_SEQUENCE_GAP");
    }

    @Test
    void acceptsNormalRunningAndSuccessfulSnapshots() {
        assertThat(checker.check(snapshot("RUNNING", "RUNNING", null, 1, 3, 2, 0))).isEmpty();
        assertThat(checker.check(snapshot("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", 0, 3, 2, 0))).isEmpty();
    }

    private static TaskStateConsistencySnapshot snapshot(String taskPhase, String runStatus,
            String manifestStatus, int activeAttempts, long processCount, long maxProcessSequence,
            long unfinishedSubtasks) {
        return new TaskStateConsistencySnapshot(UUID.randomUUID(), UUID.randomUUID(), "org-1", "tenant-1",
                taskPhase, runStatus, manifestStatus, activeAttempts, 0, processCount, maxProcessSequence,
                unfinishedSubtasks, NOW);
    }
}

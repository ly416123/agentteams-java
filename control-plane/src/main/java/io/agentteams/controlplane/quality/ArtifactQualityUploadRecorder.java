package io.agentteams.controlplane.quality;

import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

/** Records output uploads made through the authenticated internal task-MCP route. */
@Service
public final class ArtifactQualityUploadRecorder {
    private static final Logger LOG = Logger.getLogger(ArtifactQualityUploadRecorder.class.getName());

    private final TaskRunObservationRepository runObservations;
    private final ArtifactQualityLedgerService qualityLedger;
    private final Clock clock;

    public ArtifactQualityUploadRecorder(TaskRunObservationRepository runObservations,
            ArtifactQualityLedgerService qualityLedger, Clock clock) {
        this.runObservations = Objects.requireNonNull(runObservations, "runObservations");
        this.qualityLedger = Objects.requireNonNull(qualityLedger, "qualityLedger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Epoch microseconds of the authenticated upload observation fit in a signed BIGINT for the
     * supported date range; equal timestamps remain deterministically ordered by event UUID and
     * make no claim about causal order.
     */
    public void recordForActiveRun(UUID taskId, TaskFileRecord file) {
        if (!TaskFileRecord.OUTPUT.equals(file.role()) || !TaskFileRecord.AVAILABLE.equals(file.status())) return;
        try {
            ExecutionContext context = runObservations.contextForTask(taskId).orElse(null);
            UUID runId = runObservations.latestRunId(taskId).orElse(null);
            if (context == null || runId == null) return;
            var snapshot = runObservations.snapshotForRun(context, taskId, runId).orElse(null);
            if (snapshot == null || !"RUNNING".equalsIgnoreCase(snapshot.runStatus())) return;
            Instant occurredAt = clock.instant();
            long sequence = Math.addExact(Math.multiplyExact(occurredAt.getEpochSecond(), 1_000_000L),
                    occurredAt.getNano() / 1_000L);
            qualityLedger.recordUploadedOutput(context, taskId, runId, file, sequence, occurredAt);
        } catch (RuntimeException failure) {
            // Quality history is observational and cannot make a successful file upload fail.
            LOG.log(Level.WARNING, "Artifact quality file capture failed type={0}",
                    failure.getClass().getSimpleName());
        }
    }
}

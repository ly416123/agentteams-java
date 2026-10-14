package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import io.agentteams.controlplane.quality.ArtifactQualityLedgerService;

/** Stores terminal result metadata while leaving artifact bytes in object storage. */
@Service
public class TaskResultManifestService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final TaskResultManifestRepository repository;
    private final TaskResultVersionService resultVersions;
    private final TaskFileReferenceValidator fileReferences;
    private final ArtifactQualityLedgerService qualityLedger;

    @Autowired
    public TaskResultManifestService(TaskResultManifestRepository repository,
            TaskResultVersionService resultVersions, TaskFileReferenceValidator fileReferences,
            ArtifactQualityLedgerService qualityLedger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resultVersions = Objects.requireNonNull(resultVersions, "resultVersions");
        this.fileReferences = Objects.requireNonNull(fileReferences, "fileReferences");
        this.qualityLedger = Objects.requireNonNull(qualityLedger, "qualityLedger");
    }

    /** Constructor for tests that exercise task-file validation without a quality event store. */
    public TaskResultManifestService(TaskResultManifestRepository repository,
            TaskResultVersionService resultVersions, TaskFileReferenceValidator fileReferences) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resultVersions = Objects.requireNonNull(resultVersions, "resultVersions");
        this.fileReferences = Objects.requireNonNull(fileReferences, "fileReferences");
        this.qualityLedger = null;
    }

    /** Compatibility constructor for isolated manifest tests that do not publish task-file references. */
    public TaskResultManifestService(TaskResultManifestRepository repository,
            TaskResultVersionService resultVersions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resultVersions = Objects.requireNonNull(resultVersions, "resultVersions");
        this.fileReferences = null;
        this.qualityLedger = null;
    }

    @Transactional
    public TaskResultManifest publish(ExecutionContext context, TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        if (!TERMINAL_STATUSES.contains(manifest.status().trim().toUpperCase())) {
            throw new IllegalArgumentException("result manifest status must be terminal");
        }
        if (fileReferences != null) fileReferences.validate(context, manifest);
        repository.upsert(context, manifest);
        // G02 D2：SUCCEEDED 交付物同事务联动提交业务结果版本（供结果评审）；同 run 重放幂等跳过。
        resultVersions.onManifestPublished(context, manifest);
        if (qualityLedger != null) {
            try {
                qualityLedger.recordManifest(context, manifest, java.time.Instant.now());
            } catch (RuntimeException failure) {
                // Quality history is observational and cannot change the task's published result.
            }
        }
        return manifest;
    }

    public Optional<TaskResultManifest> get(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        if (visible.isEmpty()) return Optional.empty();
        return repository.find(context, taskId, runId, Set.copyOf(visible));
    }
}

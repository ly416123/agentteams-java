package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores terminal result metadata while leaving artifact bytes in object storage. */
@Service
public class TaskResultManifestService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final TaskResultManifestRepository repository;
    private final TaskResultVersionService resultVersions;

    public TaskResultManifestService(TaskResultManifestRepository repository,
            TaskResultVersionService resultVersions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resultVersions = Objects.requireNonNull(resultVersions, "resultVersions");
    }

    @Transactional
    public TaskResultManifest publish(ExecutionContext context, TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        if (!TERMINAL_STATUSES.contains(manifest.status().trim().toUpperCase())) {
            throw new IllegalArgumentException("result manifest status must be terminal");
        }
        repository.upsert(context, manifest);
        // G02 D2：SUCCEEDED 交付物同事务联动提交业务结果版本（供结果评审）；同 run 重放幂等跳过。
        resultVersions.onManifestPublished(context, manifest);
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

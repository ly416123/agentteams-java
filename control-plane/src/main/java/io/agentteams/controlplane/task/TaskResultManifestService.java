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
public final class TaskResultManifestService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final TaskResultManifestRepository repository;

    public TaskResultManifestService(TaskResultManifestRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional
    public TaskResultManifest publish(ExecutionContext context, TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        if (!TERMINAL_STATUSES.contains(manifest.status().trim().toUpperCase())) {
            throw new IllegalArgumentException("result manifest status must be terminal");
        }
        repository.upsert(context, manifest);
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

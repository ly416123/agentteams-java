package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.agentteams.controlplane.taskfile.TaskFileService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Validates explicitly selected child task files before they become parent result artifacts. */
@Service
public final class TaskFileReferenceValidator {
    private final TaskTreeService taskTree;
    private final TaskFileService taskFiles;

    public TaskFileReferenceValidator(TaskTreeService taskTree, TaskFileService taskFiles) {
        this.taskTree = Objects.requireNonNull(taskTree, "taskTree");
        this.taskFiles = Objects.requireNonNull(taskFiles, "taskFiles");
    }

    public void validate(ExecutionContext context, TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        for (TaskResultManifest.ArtifactMetadata artifact : manifest.artifacts()) {
            if (artifact.sourceTaskId() == null) continue;
            if (!"SUCCEEDED".equalsIgnoreCase(manifest.status())) {
                throw new IllegalArgumentException("task-file references require a successful parent result");
            }
            TaskFileRecord file = taskFiles.get(artifact.sourceTaskId(), artifact.sourceFileId());
            if (!TaskFileRecord.OUTPUT.equals(file.role())
                    || !TaskFileRecord.AVAILABLE.equals(file.status())) {
                throw new IllegalArgumentException("selected task file must be an available OUTPUT");
            }
            if (!artifact.name().equals(file.name())
                    || !artifact.contentType().equals(file.contentType())
                    || artifact.sizeBytes() != file.sizeBytes()
                    || !artifact.sha256().equalsIgnoreCase(file.sha256())) {
                throw new IllegalArgumentException("selected task file metadata does not match its source");
            }
            if (!artifact.sourceTaskId().equals(manifest.taskId())) {
                // Decomposition is persisted by the planning run; final results are published
                // against a separate aggregate run, so the aggregate run has no tree rows.
                List<TaskTreeNode> nodes = taskTree.findLatestForParent(context, manifest.taskId());
                boolean successfulChild = nodes.stream().anyMatch(node ->
                        artifact.sourceTaskId().equals(node.taskId())
                                && manifest.taskId().equals(node.parentTaskId())
                                && "SUCCEEDED".equalsIgnoreCase(node.status()));
                if (!successfulChild) {
                    throw new ResourceNotFoundException("successful subtask file", artifact.sourceFileId());
                }
            }
        }
    }
}

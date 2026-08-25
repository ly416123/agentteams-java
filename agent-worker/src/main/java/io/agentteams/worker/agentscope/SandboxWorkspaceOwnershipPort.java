package io.agentteams.worker.agentscope;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared or durable ownership registry for sandbox IDs and workspace paths. */
public interface SandboxWorkspaceOwnershipPort {

    Optional<WorkspaceOwner> findSandboxOwner(String providerSandboxId);

    Optional<WorkspaceOwner> findWorkspaceOwner(Path workspacePath);

    /** Must atomically claim or reject a provider sandbox owner. */
    void claimSandbox(String providerSandboxId, WorkspaceOwner owner);

    /** Must atomically claim or reject a normalized workspace path owner. */
    void claimWorkspace(Path workspacePath, WorkspaceOwner owner);

    /**
     * Must atomically claim the provider sandbox and optional workspace path.
     * Implementations backed by a durable store should use one transaction.
     */
    default void claimBinding(String providerSandboxId, Optional<Path> workspacePath, WorkspaceOwner owner) {
        claimSandbox(providerSandboxId, owner);
        workspacePath.ifPresent(path -> claimWorkspace(path, owner));
    }

    record WorkspaceOwner(UUID taskId, UUID attemptId, String scopeId) {
        public WorkspaceOwner {
            Objects.requireNonNull(taskId, "taskId must not be null");
            Objects.requireNonNull(attemptId, "attemptId must not be null");
            if (scopeId == null || scopeId.isBlank()) {
                throw new IllegalArgumentException("scopeId must be non-blank");
            }
        }
    }
}

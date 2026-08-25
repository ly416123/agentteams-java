package io.agentteams.worker.agentscope;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared or durable ownership registry for sandbox IDs and workspace paths. */
public interface SandboxWorkspaceOwnershipPort {

    /** Production implementations must be durable and shared by all worker replicas. */
    default boolean durable() {
        return false;
    }

    Optional<WorkspaceOwner> findSandboxOwner(String providerSandboxId);

    Optional<WorkspaceOwner> findWorkspaceOwner(Path workspacePath);

    /** Must atomically claim or reject a provider sandbox owner. */
    void claimSandbox(String providerSandboxId, WorkspaceOwner owner);

    /** Must atomically claim or reject a normalized workspace path owner. */
    void claimWorkspace(Path workspacePath, WorkspaceOwner owner);

    /** Claims both keys as one ownership operation, rolling back partial claims on failure. */
    default void claimSandboxAndWorkspace(String providerSandboxId, Path workspacePath, WorkspaceOwner owner) {
        claimSandbox(providerSandboxId, owner);
        try {
            if (workspacePath != null) {
                claimWorkspace(workspacePath, owner);
            }
        } catch (RuntimeException error) {
            releaseWorkspace(workspacePath, owner);
            releaseSandbox(providerSandboxId, owner);
            throw error;
        }
    }

    default void releaseSandbox(String providerSandboxId, WorkspaceOwner owner) {
    }

    default void releaseWorkspace(Path workspacePath, WorkspaceOwner owner) {
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

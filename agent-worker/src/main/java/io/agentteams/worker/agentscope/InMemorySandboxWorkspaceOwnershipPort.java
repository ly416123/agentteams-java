package io.agentteams.worker.agentscope;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic shared ownership port for tests; production should provide a durable implementation. */
public final class InMemorySandboxWorkspaceOwnershipPort implements SandboxWorkspaceOwnershipPort {
    private final Map<String, WorkspaceOwner> sandboxOwners = new HashMap<>();
    private final Map<Path, WorkspaceOwner> workspaceOwners = new HashMap<>();

    @Override
    public synchronized Optional<WorkspaceOwner> findSandboxOwner(String providerSandboxId) {
        return Optional.ofNullable(sandboxOwners.get(providerSandboxId));
    }

    @Override
    public synchronized Optional<WorkspaceOwner> findWorkspaceOwner(Path workspacePath) {
        return Optional.ofNullable(workspaceOwners.get(normalize(workspacePath)));
    }

    @Override
    public synchronized void claimSandbox(String providerSandboxId, WorkspaceOwner owner) {
        Objects.requireNonNull(providerSandboxId, "providerSandboxId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        claim(sandboxOwners, providerSandboxId, owner, "sandbox");
    }

    @Override
    public synchronized void claimWorkspace(Path workspacePath, WorkspaceOwner owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        claim(workspaceOwners, normalize(workspacePath), owner, "workspace");
    }

    @Override
    public synchronized void claimBinding(String providerSandboxId, Optional<Path> workspacePath,
            WorkspaceOwner owner) {
        Objects.requireNonNull(providerSandboxId, "providerSandboxId must not be null");
        Objects.requireNonNull(workspacePath, "workspacePath must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Path normalizedWorkspace = workspacePath.map(InMemorySandboxWorkspaceOwnershipPort::normalize).orElse(null);
        WorkspaceOwner existingSandbox = sandboxOwners.get(providerSandboxId);
        if (existingSandbox != null && !existingSandbox.equals(owner)) {
            throw new IllegalArgumentException("sandbox ownership conflict");
        }
        WorkspaceOwner existingWorkspace = normalizedWorkspace == null ? null : workspaceOwners.get(normalizedWorkspace);
        if (existingWorkspace != null && !existingWorkspace.equals(owner)) {
            throw new IllegalArgumentException("workspace ownership conflict");
        }
        sandboxOwners.putIfAbsent(providerSandboxId, owner);
        if (normalizedWorkspace != null) {
            workspaceOwners.putIfAbsent(normalizedWorkspace, owner);
        }
    }

    private static <K> void claim(Map<K, WorkspaceOwner> owners, K key, WorkspaceOwner owner, String kind) {
        WorkspaceOwner previous = owners.get(key);
        if (previous != null && !previous.equals(owner)) {
            throw new IllegalArgumentException(kind + " ownership conflict");
        }
        owners.putIfAbsent(key, owner);
    }

    private static Path normalize(Path path) {
        Objects.requireNonNull(path, "workspacePath must not be null");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("workspacePath must be absolute");
        }
        return path.normalize();
    }
}

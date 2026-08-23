package io.agentteams.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates immutable runtime configuration versions. Staging never changes
 * the active version; only a successful prepared activation does.
 */
public final class RuntimeConfigCoordinator {
    private final RuntimeConfigApplier applier;
    private final Map<UUID, PendingStage> pending = new HashMap<>();
    private RuntimeConfigSnapshot active;

    public RuntimeConfigCoordinator(RuntimeConfigApplier applier) {
        this.applier = Objects.requireNonNull(applier, "applier");
    }

    public synchronized Optional<RuntimeConfigSnapshot> activeSnapshot() {
        return Optional.ofNullable(active);
    }

    public synchronized RuntimeConfigStage stage(RuntimeConfigSnapshot snapshot) {
        return stage(snapshot, false);
    }

    private RuntimeConfigStage stage(RuntimeConfigSnapshot snapshot, boolean rollback) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateNextVersion(snapshot, rollback);
        final RuntimeConfigPrepared prepared;
        try {
            prepared = Objects.requireNonNull(applier.stage(snapshot, activeSnapshot()),
                    "applier returned null prepared config");
        } catch (RuntimeException error) {
            throw new RuntimeConfigApplyException("configuration staging failed", error);
        }
        RuntimeConfigStage stage = new RuntimeConfigStage(UUID.randomUUID(), snapshot);
        pending.put(stage.id(), new PendingStage(snapshot, prepared, rollback));
        return stage;
    }

    public synchronized RuntimeConfigApplyResult activate(RuntimeConfigStage stage) {
        Objects.requireNonNull(stage, "stage");
        PendingStage pendingStage = pending.remove(stage.id());
        if (pendingStage == null || !pendingStage.snapshot().equals(stage.snapshot())) {
            throw new IllegalArgumentException("unknown or mismatched configuration stage");
        }
        try {
            validateNextVersion(pendingStage.snapshot(), pendingStage.rollback());
        } catch (RuntimeException error) {
            discardQuietly(pendingStage.prepared(), error);
            throw error;
        }
        try {
            pendingStage.prepared().activate();
        } catch (RuntimeException error) {
            discardQuietly(pendingStage.prepared(), error);
            throw new RuntimeConfigApplyException("configuration activation failed", error);
        }
        active = pendingStage.snapshot();
        return new RuntimeConfigApplyResult(RuntimeConfigApplyResult.Status.ACTIVATED, active);
    }

    public synchronized void discard(RuntimeConfigStage stage) {
        Objects.requireNonNull(stage, "stage");
        PendingStage pendingStage = pending.remove(stage.id());
        if (pendingStage == null || !pendingStage.snapshot().equals(stage.snapshot())) {
            throw new IllegalArgumentException("unknown or mismatched configuration stage");
        }
        pendingStage.prepared().discard();
    }

    public synchronized RuntimeConfigApplyResult apply(RuntimeConfigSnapshot snapshot) {
        return apply(snapshot, false);
    }

    public synchronized RuntimeConfigApplyResult apply(RuntimeConfigSnapshot snapshot, boolean rollback) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (active != null && active.version() == snapshot.version()) {
            if (active.checksum().equals(snapshot.checksum())) {
                return new RuntimeConfigApplyResult(RuntimeConfigApplyResult.Status.ALREADY_ACTIVE, active);
            }
            throw new IllegalArgumentException("configuration version already has another checksum");
        }
        return activate(stage(snapshot, rollback));
    }

    private void validateNextVersion(RuntimeConfigSnapshot snapshot, boolean rollback) {
        if (active == null && rollback) {
            throw new IllegalArgumentException("cannot roll back without an active configuration");
        }
        if (!rollback && active != null && snapshot.version() <= active.version()) {
            throw new IllegalArgumentException("configuration version must be newer than active version");
        }
        if (rollback && active != null && snapshot.version() >= active.version()) {
            throw new IllegalArgumentException("rollback version must be older than active version");
        }
    }

    private static void discardQuietly(RuntimeConfigPrepared prepared, RuntimeException original) {
        try {
            prepared.discard();
        } catch (RuntimeException discardError) {
            original.addSuppressed(discardError);
        }
    }

    private record PendingStage(RuntimeConfigSnapshot snapshot, RuntimeConfigPrepared prepared, boolean rollback) {
    }
}
